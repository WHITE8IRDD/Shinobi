package com.shinobi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/** HTTP failures are service failures, not evidence that the phone is offline. */
internal class HttpFailure(val status: Int, val retryAfterMs: Long? = null) : IOException("YouTube returned $status")
internal class GeneralTrendingUnavailable : IOException("YouTube retired the combined regional Trending feed")
internal class EmptyFeedException : IOException("YouTube returned no usable suggestions")
internal class ServiceResponseException(cause: Throwable? = null) : IOException("YouTube returned an unreadable response", cause)

enum class ConnectionState { Unknown, Offline, Unvalidated, CaptivePortal, Online }

/** Read-only recovery. Never use this for likes, subscriptions, comments or other writes. */
internal object ReadRecovery {
    fun retryable(error: Exception): Boolean = when (error) {
        is CancellationException, is NotSignedInException -> false
        is HttpFailure -> error.status in setOf(400, 401, 403, 408, 429) || error.status >= 500
        is IOException -> true
        else -> false
    }

    suspend fun <T> run(
        attempts: Int = 3,
        jitter: () -> Long = { Random.nextLong(0, 201) },
        block: suspend (attempt: Int) -> T,
    ): T {
        require(attempts in 1..3)
        repeat(attempts) { attempt ->
            try { return block(attempt) }
            catch (error: Exception) {
                if (!retryable(error) || attempt == attempts - 1) throw error
                // Respect numeric Retry-After without leaving the screen spinning indefinitely.
                val backoff = 500L * (1L shl attempt) + jitter()
                delay(maxOf(backoff, (error as? HttpFailure)?.retryAfterMs ?: 0L).coerceAtMost(5_000))
            }
        }
        error("unreachable")
    }

    fun message(error: Throwable, connection: ConnectionState): String = when {
        connection == ConnectionState.Offline -> "No internet connection. Connect to Wi-Fi or mobile data and try again."
        connection == ConnectionState.CaptivePortal -> "This Wi-Fi network requires sign-in. Sign in to the network and try again."
        error is HttpFailure && error.status == 429 -> "YouTube is rate-limiting requests right now. Try again in a minute."
        error is HttpFailure && error.status in setOf(401, 403) -> "YouTube rejected this request. Try again or sign in again."
        error is SocketTimeoutException -> "YouTube took too long to respond. Please try again."
        error is EmptyFeedException -> "YouTube has no suggestions available right now. Please try again."
        else -> "YouTube is temporarily unavailable. Please try again."
    }
}
