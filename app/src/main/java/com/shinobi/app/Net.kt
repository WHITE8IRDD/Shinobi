package com.shinobi.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** One HTTP client for the whole app: extractor requests and media playback share it. */
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

    // Separate deadlines for small API calls; do not impose these on media streams.
    val apiClient: OkHttpClient by lazy {
        client.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    }
    val suggestionClient: OkHttpClient by lazy {
        apiClient.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    }
    private var connectivity: android.net.ConnectivityManager? = null

    fun init(context: android.content.Context) {
        connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    }

    /** A missing validation probe is NOT proof of no internet (VPNs often block it). */
    fun connectionState(): ConnectionState {
        val manager = connectivity ?: return ConnectionState.Unknown
        return try {
            val network = manager.activeNetwork ?: return ConnectionState.Offline
            val caps = manager.getNetworkCapabilities(network) ?: return ConnectionState.Unknown
            when {
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> ConnectionState.CaptivePortal
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> ConnectionState.Online
                else -> ConnectionState.Unvalidated
            }
        } catch (_: SecurityException) { ConnectionState.Unknown }
    }

    /** True on Wi-Fi or any other connection the system doesn't count as metered. */
    fun isUnmetered(): Boolean {
        val manager = connectivity ?: return false
        return try {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (_: SecurityException) { false }
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
        val instance: NewPipeDownloader by lazy { NewPipeDownloader(Net.client) }
    }
}
