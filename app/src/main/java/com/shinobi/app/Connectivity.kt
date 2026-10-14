package com.shinobi.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What the phone's network actually is, from the platform — never guessed from one failed
 * request. A request that fails while Wi-Fi is up and validated is a YouTube problem, not a
 * connection problem, and the app says so (owner report, 2026-10-14: Home blamed the network
 * for ordinary API failures).
 *
 * The state is deliberately optimistic: [online] starts true and only goes false when the OS
 * itself reports no usable network, so Shinobi can never claim there is no internet just
 * because it has not been told yet.
 */
object Connectivity {
    /** True unless the platform says there is no usable network. Optimistic by design. */
    var online by mutableStateOf(true)
        private set

    /** True when the current network is metered (mobile data / hotspot). */
    var metered by mutableStateOf(false)
        private set

    /**
     * Bumped when the network comes back or the default network changes (Wi-Fi ↔ mobile,
     * a reconnect). Screens watch it to reload on their own instead of waiting for a tap.
     */
    var generation by mutableIntStateOf(0)
        private set

    private var manager: ConnectivityManager? = null
    private var current: Network? = null
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        val cm = runCatching { context.applicationContext.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        if (cm == null) return
        manager = cm
        apply(cm, cm.activeNetwork, null)
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = apply(cm, network, null)
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                        apply(cm, network, capabilities)

                    override fun onLost(network: Network) = apply(cm, null, null)
                },
            )
        }
    }

    private fun apply(cm: ConnectivityManager, network: Network?, capabilities: NetworkCapabilities?) {
        val active = network ?: runCatching { cm.activeNetwork }.getOrNull()
        val caps = capabilities ?: active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val nowOnline = caps != null && usable(caps)
        val switched = active != current
        val cameBack = nowOnline && !online
        current = active
        online = nowOnline
        metered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (cameBack || switched) generation++
    }

    /**
     * A network Shinobi can use. `NET_CAPABILITY_INTERNET` is the platform's own statement
     * that this network routes to the internet; `NET_CAPABILITY_VALIDATED` is only a captive
     * portal probe result and is routinely false on perfectly working networks (VPNs, blocked
     * probes, a Wi-Fi association that just completed), so it is never read as "offline".
     */
    private fun usable(caps: NetworkCapabilities): Boolean {
        val transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        return transport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Re-reads the platform state on demand (a "Try again" tap wants the truth right now). */
    fun recheck(): Boolean {
        val cm = manager ?: return online
        apply(cm, cm.activeNetwork, null)
        return online
    }

    /**
     * Suspends until the network is back, bounded by [timeoutMs]. Returns true when online.
     * Never blocks when the platform state is unavailable (unit tests, unusual devices):
     * waiting for a callback that will not come would be worse than failing fast.
     */
    suspend fun awaitOnline(timeoutMs: Long): Boolean {
        if (online) return true
        if (manager == null) return true
        return withTimeoutOrNull(timeoutMs) { snapshotFlow { online }.first { it } } != null
    }
}

/** Why a request failed, so the message shown can be the true one. */
enum class NetFailure {
    /** The platform says there is no usable network. */
    Offline,

    /** A network exists but the name did not resolve (DNS, VPN, captive portal). */
    Dns,

    /** The peer did not answer in time. */
    Timeout,

    /** TLS could not be established (device clock, filtering proxy, MITM firewall). */
    Tls,

    /** The connection was refused, dropped or reset. */
    Unreachable,

    /** HTTP 429 — YouTube is throttling this address or client. */
    RateLimited,

    /** HTTP 5xx — YouTube's side is failing. The connection is fine. */
    Server,

    /** HTTP 401/403 on an authenticated request — the session is gone. */
    Session,

    /** YouTube wants proof this is not a bot; a different client usually answers. */
    Blocked,

    /** HTTP 404 — nothing at that address. */
    NotFound,

    /** Anything else. Reported as an unknown problem, never as a connection problem. */
    Unknown,
}

/** An [IOException] that knows why it happened and which HTTP status produced it, if any. */
class NetException(
    val failure: NetFailure,
    val code: Int = -1,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    companion object {
        /**
         * Classifies an HTTP status. 401/403 on a signed-in request is a dead session; the
         * same status anonymously is YouTube's bot check, which another client can pass.
         */
        fun http(code: Int, anonymous: Boolean = true): NetException {
            val failure = when {
                code == 408 -> NetFailure.Timeout
                code == 429 -> NetFailure.RateLimited
                code == 401 || code == 403 -> if (anonymous) NetFailure.Blocked else NetFailure.Session
                code == 404 -> NetFailure.NotFound
                code in 500..599 -> NetFailure.Server
                else -> NetFailure.Unknown
            }
            return NetException(failure, code, "YouTube returned $code")
        }
    }
}

/** Turns any throwable into a [NetFailure] and into a sentence that is actually true. */
object NetErrors {
    /** Walks the cause chain (bounded) and returns the first failure it recognises. */
    fun classify(t: Throwable?): NetFailure {
        if (t == null) return NetFailure.Unknown
        if (t is CancellationException) return NetFailure.Unknown
        for (e in generateSequence(t) { it.cause }.take(8).toList()) {
            read(e)?.let { return it }
        }
        return if (!Connectivity.online) NetFailure.Offline else NetFailure.Unknown
    }

    private fun read(e: Throwable): NetFailure? = when {
        e is NetException -> e.failure
        e is NotSignedInException -> NetFailure.Session
        e is CancellationException -> NetFailure.Unknown
        // The extractor's own signals, checked before the generic IO families they extend.
        e is org.schabi.newpipe.extractor.exceptions.ReCaptchaException -> NetFailure.RateLimited
        e is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException -> NetFailure.Blocked
        e is SocketTimeoutException -> NetFailure.Timeout
        e is InterruptedIOException -> if ("timeout" in e.message.orEmpty().lowercase()) NetFailure.Timeout else null
        e is UnknownHostException -> if (Connectivity.online) NetFailure.Dns else NetFailure.Offline
        e is NoRouteToHostException || e is ConnectException ->
            if (Connectivity.online) NetFailure.Unreachable else NetFailure.Offline
        e is SSLException -> NetFailure.Tls
        e is EOFException || e is SocketException || "StreamResetException" == e.javaClass.simpleName ->
            if (Connectivity.online) NetFailure.Unreachable else NetFailure.Offline
        e is IOException -> fromStatus(e.message) ?: if (Connectivity.online) NetFailure.Unreachable else NetFailure.Offline
        else -> null
    }

    /** Several call sites report a status in prose ("YouTube returned 503"); read it back. */
    private val STATUS = Regex("""(?:^|\D)([1-5]\d{2})(?:\D|$)""")

    internal fun fromStatus(message: String?): NetFailure? {
        val text = message ?: return null
        if (!text.contains("YouTube") && !text.contains("HTTP") && !text.contains("ping")) return null
        val code = STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return NetException.http(code).failure
    }

    fun asException(t: Throwable?): NetException {
        if (t is NetException) return t
        val failure = classify(t)
        return NetException(failure, -1, message(failure, -1), t)
    }

    /**
     * The honest sentence for [failure]. The rule this exists to enforce: "no internet" is
     * only ever said when the platform reports no usable network. Everything else names the
     * real problem — YouTube throttling, YouTube failing, a timeout, DNS, TLS — and always
     * offers the next action.
     */
    fun message(failure: NetFailure, code: Int = -1): String = when (failure) {
        NetFailure.Offline -> Loc.t("You're offline. Shinobi will reload by itself as soon as the connection is back.")
        NetFailure.Dns -> Loc.t("Your device couldn't resolve youtube.com. That is usually DNS, a VPN or a sign-in page on this network — not a missing connection.")
        NetFailure.Timeout -> Loc.t("YouTube took too long to answer. Try again.")
        NetFailure.Tls -> Loc.t("A secure connection to YouTube failed. Check the device's date and time, or any VPN or firewall on this network.")
        NetFailure.Unreachable -> Loc.t("Couldn't reach YouTube just now, though this device is connected. Try again.")
        NetFailure.RateLimited -> Loc.t("YouTube is rate-limiting requests right now. Try again in a minute.")
        NetFailure.Server -> if (code > 0) Loc.t("YouTube is temporarily unavailable on its side") + " (HTTP $code). " + Loc.t("Your connection is fine — try again.")
        else Loc.t("YouTube is temporarily unavailable on its side. Your connection is fine — try again.")
        NetFailure.Session -> Loc.t("YouTube rejected the session. Try signing in again.")
        NetFailure.Blocked -> Loc.t("YouTube is asking to confirm you're not a bot. Try again in a few minutes.")
        NetFailure.NotFound -> Loc.t("YouTube has nothing at that address.")
        NetFailure.Unknown -> Loc.t("Something went wrong loading this. Try again.")
    }

    /**
     * Worth another attempt **on the same route**. A bot check is not: repeating the identical
     * request to the identical client gets the identical refusal, but a different client
     * usually passes — which is [stopsLadder]'s business, not this one's.
     */
    fun isTransient(failure: NetFailure): Boolean = when (failure) {
        NetFailure.Offline, NetFailure.Dns, NetFailure.Timeout, NetFailure.Unreachable,
        NetFailure.RateLimited, NetFailure.Server,
        -> true

        NetFailure.Tls, NetFailure.Blocked, NetFailure.Session, NetFailure.NotFound,
        NetFailure.Unknown,
        -> false
    }

    fun isTransient(t: Throwable?): Boolean =
        t !is CancellationException && isTransient(classify(t))

    /**
     * A failure no other client or host can fix, so the route ladder stops here. Everything
     * else — a refusal aimed at one client, a timeout, a 5xx, a TLS or DNS problem on one
     * host — is exactly what an alternate InnerTube client exists for.
     */
    fun stopsLadder(failure: NetFailure): Boolean =
        failure == NetFailure.Session || failure == NetFailure.NotFound

    fun stopsLadder(t: Throwable?): Boolean = stopsLadder(classify(t))
}

/**
 * Exponential backoff with equal jitter. Retry timing is shared by the transport interceptor,
 * the InnerTube route ladder and the feed loaders so one policy governs them all.
 */
object Retry {
    /** Whole-feed attempts before an error is allowed to reach the screen. */
    const val FEED_ATTEMPTS = 3
    const val BASE_MS = 320L
    const val MAX_MS = 4_000L

    /** Hard ceiling on one logical request, so a dead endpoint can never stall a screen. */
    const val DEADLINE_MS = 15_000L

    /** How long a retry waits for the network to come back before giving up on it. */
    const val OFFLINE_WAIT_MS = 6_000L

    /**
     * [attempt] is 1-based. Equal jitter — half the exponential delay fixed, half random —
     * so the six parallel Home sources never retry in lockstep and re-throttle themselves.
     */
    fun backoffMs(attempt: Int, base: Long = BASE_MS, max: Long = MAX_MS): Long {
        val shift = (attempt - 1).coerceIn(0, 6)
        val exp = (base * (1L shl shift)).coerceIn(0L, max)
        if (exp <= 0L) return 0L
        return exp / 2 + kotlin.random.Random.nextLong(exp / 2 + 1)
    }

    /** Blocking sibling of [backoffMs], for the extractor's synchronous call path. */
    fun sleepBlocking(attempt: Int, base: Long = BASE_MS, max: Long = MAX_MS) {
        val ms = backoffMs(attempt, base, max)
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
