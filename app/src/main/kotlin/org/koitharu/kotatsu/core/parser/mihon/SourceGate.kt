package org.zeravrix.kotatsu.core.parser.mihon

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Gatekeeper that limits concurrency per source (max 2 requests in flight)
 * and applies exponential backoff on HTTP 429 (Too Many Requests), honoring Retry-After.
 * Enforces a strict 25-second execution timeout to prevent infinite UI loading loops.
 */
object SourceGate {

    private const val TAG = "SourceGate"
    private const val MAX_CONCURRENT_REQUESTS = 3
    private const val MIN_GAP_MS = 40L
    private const val MAX_RETRIES = 3
    private const val TIMEOUT_MS = 25_000L

    private val fallbackSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val semaphores = ConcurrentHashMap<Long, Semaphore>()
    private val lastRequestTimes = ConcurrentHashMap<Long, Long>()

    private fun getSemaphore(sourceId: Long): Semaphore =
        if (sourceId <= 0L) fallbackSemaphore
        else semaphores.getOrPut(sourceId) { Semaphore(MAX_CONCURRENT_REQUESTS) }

    suspend fun <T> execute(sourceId: Long, initialDelayMs: Long = 1000L, block: suspend () -> T): T {
        val semaphore = getSemaphore(sourceId)

        return semaphore.withPermit {
            // Enforce minimum gap between request starts
            val now = System.currentTimeMillis()
            val lastTime = lastRequestTimes[sourceId] ?: 0L
            val elapsed = now - lastTime
            if (elapsed < MIN_GAP_MS) {
                delay(MIN_GAP_MS - elapsed)
            }
            lastRequestTimes[sourceId] = System.currentTimeMillis()

            var attempt = 0
            var delayMs = initialDelayMs

            while (true) {
                try {
                    return@withPermit withTimeout(TIMEOUT_MS) {
                        block()
                    }
                } catch (e: Throwable) {
                    val is429 = isHttp429(e)
                    if (is429 && attempt < MAX_RETRIES) {
                        attempt++
                        val retryAfterSec = extractRetryAfterSeconds(e)
                        val waitTime = if (retryAfterSec != null && retryAfterSec > 0) {
                            retryAfterSec * 1000L
                        } else {
                            delayMs
                        }
                        runCatching {
                            Log.w(TAG, "Source $sourceId returned HTTP 429. Retrying in ${waitTime}ms (attempt $attempt/$MAX_RETRIES)")
                        }
                        delay(waitTime)
                        delayMs *= 2
                    } else {
                        throw e
                    }
                }
            }
            error("Unreachable")
        }
    }

    private fun isHttp429(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        if (msg.contains("429", ignoreCase = true) || msg.contains("Too Many Requests", ignoreCase = true)) {
            return true
        }
        val cause = e.cause
        return cause != null && isHttp429(cause)
    }

    private fun extractRetryAfterSeconds(e: Throwable): Long? {
        val msg = e.message.orEmpty()
        val match = Regex("""Retry-After:?\s*(\d+)""", RegexOption.IGNORE_CASE).find(msg)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull()
    }
}
