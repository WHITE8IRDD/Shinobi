package com.shinobi.app

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One small request after typing settles; cancellation also closes the underlying socket. */
object SuggestionApi {
    suspend fun fetch(query: String, language: String): List<String> {
        val url = "https://suggestqueries.google.com/complete/search".toHttpUrl().newBuilder()
            .addQueryParameter("client", "firefox").addQueryParameter("ds", "yt")
            .addQueryParameter("hl", language).addQueryParameter("q", query).build()
        val text = Net.client.newCall(Request.Builder().url(url).build()).awaitText()
        return withContext(Dispatchers.Default) { parse(text) }
    }

    internal fun parse(text: String): List<String> {
        if (text.length > 100_000) return emptyList()
        val array = JSONArray(text).optJSONArray(1) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            (array.opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
        }.distinct().take(8)
    }
}

/** Shared cancellable HTTP adapter; no blocking network work on the composition thread. */
internal suspend fun okhttp3.Call.awaitText(onHeaders: (okhttp3.Response) -> Unit = {}): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            try {
                val text = response.use {
                    onHeaders(it)
                    if (!it.isSuccessful) throw IOException("YouTube returned ${it.code}")
                    it.body?.string().orEmpty()
                }
                if (continuation.isActive) continuation.resume(text)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

@Stable
class SearchSuggestions(
    private val scope: CoroutineScope,
    private val loader: suspend (String, String) -> List<String> = SuggestionApi::fetch,
    private val history: () -> List<String> = { LocalHistory.recentQueries },
) {
    var items by mutableStateOf<List<String>>(emptyList())
        private set
    var visible by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var revision = 0
    private val cache = object : LinkedHashMap<String, Pair<Long, List<String>>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<String>>>?) = size > 32
    }

    fun dismiss() {
        revision++
        job?.cancel()
        visible = false
        items = emptyList()
    }

    fun update(input: String, language: String) {
        dismiss()
        val q = input.trim().take(200)
        if (q.isBlank()) return
        val current = revision
        val local = history().filter { it.startsWith(q, ignoreCase = true) }.take(4)
        items = local
        visible = true
        job = scope.launch {
            delay(250)
            val key = "$language|$q"
            val cached = cache[key]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }
            val remote = try {
                cached?.second ?: loader(q, language).take(8).also { cache[key] = System.currentTimeMillis() to it }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() } // Offline: prefix-matched local history remains usable.
            if (isActive && current == revision) items = (local + remote).distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(8)
        }
    }
}
