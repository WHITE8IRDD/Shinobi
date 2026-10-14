package com.shinobi.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One HTTP stack for the whole app, split by what the request is for:
 *
 *  - [client] carries media (Media3 range requests against the video servers) and anything
 *    whose failure the player already recovers from itself. It never retries at this layer:
 *    replaying a byte-range request would corrupt a stream.
 *  - [apiClient] carries every metadata request — the extractor, InnerTube, search
 *    suggestions, captions, comments, SponsorBlock. It has shorter timeouts and retries a
 *    transient failure by itself with exponential backoff, so one dropped packet or a
 *    momentary 5xx never reaches the screen as an error.
 */
object Net {
    // NewPipe uses a desktop Firefox UA; YouTube serves the web client pages it parses.
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Idle connections to the video servers stay warm between videos.
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * Shares [client]'s connection pool and dispatcher (so it costs nothing extra) but fails
     * faster and retries: a metadata request that hangs for 30 s is what makes a feed look
     * broken, and a 503 that is retried once is invisible.
     */
    val apiClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .addInterceptor(TransientRetry)
            .build()
    }

    /** The video-server host YouTube last handed out; usually the same one next time. */
    @Volatile private var mediaHost: String? = null

    fun noteMediaHost(url: String?) {
        url?.toHttpUrlOrNull()?.host?.let { mediaHost = it }
    }

    /**
     * Opens (DNS + TLS) a connection to the likely video server while the stream data is
     * still being extracted, so the player's first requests skip ~0.7 s of setup. A cheap
     * request to /generate_204; if YouTube picks another host this time, nothing is lost.
     */
    fun prewarmMediaConnection() {
        val host = mediaHost ?: return
        val request = okhttp3.Request.Builder().url("https://$host/generate_204").head().header("User-Agent", USER_AGENT).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
        })
    }
}

/**
 * Retries a transport failure, a 429 or a 5xx on the same route, with exponential backoff.
 *
 * Deliberately narrow about what it touches: media hosts and partial (Range) requests are
 * passed straight through, a cancelled call is never revived, a body that cannot be sent
 * twice is not, and a status that is a fact rather than a hiccup (401, 404) is reported as
 * is. Everything above this layer — the InnerTube route ladder, the feed loaders — then sees
 * only a failure that genuinely survived three attempts.
 */
internal object TransientRetry : Interceptor {
    private const val ATTEMPTS = 3
    private const val MAX_SLEEP_MS = 1_500L

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (!retryable(request)) return chain.proceed(request)
        var lastFailure: IOException? = null
        for (attempt in 1..ATTEMPTS) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                val response = chain.proceed(request)
                if (response.code != 429 && response.code !in 500..599) return response
                val code = response.code
                val retryAfter = response.header("Retry-After")?.trim()?.toIntOrNull()
                response.close()
                if (attempt == ATTEMPTS) throw NetException.http(code)
                pause(attempt, retryAfter, chain)
            } catch (e: IOException) {
                // Cancellation is the caller's decision, not a network fault.
                if (chain.call().isCanceled()) throw e
                lastFailure = e
                val failure = NetErrors.classify(e)
                if (!NetErrors.isTransient(failure)) throw e
                if (attempt == ATTEMPTS) throw e
                pause(attempt, null, chain)
            }
        }
        throw lastFailure ?: IOException("Request failed after $ATTEMPTS attempts")
    }

    private fun pause(attempt: Int, retryAfterSec: Int?, chain: Interceptor.Chain) {
        if (chain.call().isCanceled()) return
        val backoff = Retry.backoffMs(attempt).coerceAtMost(MAX_SLEEP_MS)
        val honoured = (retryAfterSec ?: 0).coerceIn(0, 5) * 1_000L
        val ms = maxOf(backoff, honoured)
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Media and partial requests belong to the player, which has its own recovery. */
    private fun retryable(request: okhttp3.Request): Boolean {
        val host = request.url.host
        if (host.endsWith("googlevideo.com") || host.endsWith("gvt1.com")) return false
        if (request.header("Range") != null) return false
        val body = request.body ?: return true
        return !body.isOneShot() && !body.isDuplex()
    }
}

/** Bridges NewPipeExtractor's HTTP abstraction onto OkHttp. */
class NewPipeDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", Net.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            // YouTube answers rate-limited clients with 429 + a captcha page.
            if (response.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            val text = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                if (PlayerFallback.handles(request.url())) PlayerFallback.substitute(request.dataToSend(), text) else text,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        val instance: NewPipeDownloader by lazy { NewPipeDownloader(Net.apiClient) }
    }
}
