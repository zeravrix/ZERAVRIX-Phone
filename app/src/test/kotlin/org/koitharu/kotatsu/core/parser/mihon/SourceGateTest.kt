package org.zeravrix.kotatsu.core.parser.mihon

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SourceGateTest {

    @Test
    fun `limits concurrency to max 3 simultaneous executions`() = runBlocking {
        val activeCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val sourceId = 9999L

        val jobs = List(10) {
            async {
                SourceGate.execute(sourceId) {
                    val current = activeCount.incrementAndGet()
                    maxObserved.updateAndGet { maxOf(it, current) }
                    delay(50)
                    activeCount.decrementAndGet()
                }
            }
        }

        jobs.awaitAll()
        assertTrue("Max observed concurrency should be <= 3, was ${maxObserved.get()}", maxObserved.get() <= 3)
    }

    @Test
    fun `retries on HTTP 429 and succeeds on later attempt`() = runBlocking {
        val attempts = AtomicInteger(0)
        val sourceId = 8888L

        val result = SourceGate.execute(sourceId, initialDelayMs = 10L) {
            val count = attempts.incrementAndGet()
            if (count < 3) {
                throw IOException("HTTP 429: Too Many Requests")
            }
            "Success on attempt $count"
        }

        assertEquals("Success on attempt 3", result)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `fails after maximum retries exceeded for HTTP 429`() = runBlocking {
        val attempts = AtomicInteger(0)
        val sourceId = 7777L

        try {
            SourceGate.execute(sourceId, initialDelayMs = 10L) {
                attempts.incrementAndGet()
                throw IOException("HTTP 429: Too Many Requests")
            }
            fail("Expected IOException to be thrown")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("429") == true)
            assertEquals(4, attempts.get()) // 1 initial + 3 retries
        }
    }
}
