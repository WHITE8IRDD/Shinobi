package com.shinobi.app

import org.json.JSONObject

/**
 * The InnerTube routes Shinobi can reach the same endpoint through, in the order they are
 * tried.
 *
 * YouTube throttles, bot-checks and occasionally fails **per client** as well as per address,
 * so a refusal aimed at the web client is not a refusal aimed at the Android or iOS one. When
 * the primary route fails with anything that is not a permanent fact (a dead session, a real
 * 404), the next route is asked for the same page instead of the screen giving up and blaming
 * the phone's connection.
 *
 * The ladder is used only for **anonymous** requests. A signed-in session is a bundle of
 * cookies, a `SAPISIDHASH` signature and a `visitorData` token that all belong to the web
 * client on `www.youtube.com`; replaying them as another client would be both wrong and a
 * good way to lose the account session, so those requests keep one route and rely on the
 * transport's own retry instead.
 *
 * Client names and versions mirror NewPipeExtractor's `ClientsConstants`. Only the web
 * version is scraped live ([Account.clientVersion]); the fallbacks are pinned, because a
 * slightly older client version is still served and a scraped one is not available when the
 * scrape is what failed.
 */
internal object InnerTubeRoutes {

    data class Route(
        val id: String,
        val host: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        /** The `X-YouTube-Client-Name` numeric id that belongs to [clientName]. */
        val clientNameId: String,
        val platform: String?,
        val osName: String?,
        val osVersion: String?,
        val formFactor: String?,
        val deviceModel: String?,
    ) {
        /** The web client's version is read from youtube.com itself; the others are pinned. */
        val version: String get() = if (id == WEB_ID) Account.clientVersion() else clientVersion

        /** Browser clients send Origin/Referer; the app clients must not pretend to be one. */
        val isBrowserClient: Boolean get() = clientName == "WEB" || clientName == "MWEB"

        fun origin(): String = "https://$host"

        fun clientContext(anonymous: Boolean, country: String): JSONObject = JSONObject()
            .put("clientName", clientName)
            .put("clientVersion", version)
            .put("hl", Loc.language)
            .put("gl", country)
            .apply { platform?.let { put("platform", it) } }
            .apply { osName?.let { put("osName", it) } }
            .apply { osVersion?.let { put("osVersion", it) } }
            .apply { deviceModel?.let { put("deviceModel", it) } }
            .apply { formFactor?.let { put("clientFormFactor", it) } }
            .apply { if (!anonymous) Account.visitorData()?.let { put("visitorData", it) } }
    }

    private const val WEB_ID = "web"

    /** Desktop web: what the extractor and the signed-in session both use. */
    val WEB = Route(
        id = WEB_ID,
        host = "www.youtube.com",
        clientName = "WEB",
        clientVersion = "2.20260901.00.00",
        userAgent = Net.USER_AGENT,
        clientNameId = "1",
        platform = "DESKTOP",
        osName = null,
        osVersion = null,
        formFactor = null,
        deviceModel = null,
    )

    /** Mobile web on the mobile host: a separate quota and a separate bot-check budget. */
    val MWEB = Route(
        id = "mweb",
        host = "m.youtube.com",
        clientName = "MWEB",
        clientVersion = "2.20260901.00.00",
        userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        clientNameId = "2",
        platform = "MOBILE",
        osName = null,
        osVersion = null,
        formFactor = "SMALL_FORM_FACTOR",
        deviceModel = null,
    )

    /** The Android app client: the most generously rate-limited of the public clients. */
    val ANDROID = Route(
        id = "android",
        host = "www.youtube.com",
        clientName = "ANDROID",
        clientVersion = "19.09.37",
        userAgent = "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip",
        clientNameId = "3",
        platform = "MOBILE",
        osName = "Android",
        osVersion = "14",
        formFactor = "SMALL_FORM_FACTOR",
        deviceModel = null,
    )

    /** The iOS app client: last resort, and the one [PlayerFallback] already trusts. */
    val IOS = Route(
        id = "ios",
        host = "www.youtube.com",
        clientName = "IOS",
        clientVersion = "19.09.3",
        userAgent = "com.google.ios.youtube/19.09.3 (iPhone14,3; U; CPU iOS 17_4_0 like Mac OS X)",
        clientNameId = "5",
        platform = "MOBILE",
        osName = "iOS",
        osVersion = "17.4.0",
        formFactor = "SMALL_FORM_FACTOR",
        deviceModel = "iPhone14,3",
    )

    /** Anonymous requests walk the whole ladder; signed-in ones stay on the web client. */
    val ANONYMOUS: List<Route> = listOf(WEB, MWEB, ANDROID, IOS)
    val SIGNED_IN: List<Route> = listOf(WEB)

    /**
     * The route that last answered, so a working fallback is not paid for again on the next
     * request. Reset by an explicit user retry, which should always start from the primary.
     */
    @Volatile
    var preferred: Route = WEB
        private set

    fun noteHealthy(route: Route) {
        preferred = route
    }

    fun reset() {
        preferred = WEB
    }

    /** The ladder for this request, with the last healthy route moved to the front. */
    fun plan(anonymous: Boolean): List<Route> {
        val ladder = if (anonymous) ANONYMOUS else SIGNED_IN
        val first = preferred
        return if (first in ladder) listOf(first) + ladder.filter { it != first } else ladder
    }
}
