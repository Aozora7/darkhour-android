package one.aozora.darkhour.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthConnectReadRetryTest {
    @Test
    fun retriesRateLimitsWithCappedExponentialBackoff() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = retryHealthConnectRateLimitedRead(
            policy = HealthConnectReadRetryPolicy(
                maxAttempts = 5,
                initialDelayMillis = 100L,
                maximumDelayMillis = 250L,
            ),
            delayMillis = delays::add,
        ) {
            attempts += 1
            if (attempts < 5) throw rateLimitFailure()
            "success"
        }

        assertEquals("success", result)
        assertEquals(5, attempts)
        assertEquals(listOf(100L, 200L, 250L, 250L), delays)
    }

    @Test
    fun doesNotRetryUnrelatedFailures() {
        var attempts = 0

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                retryHealthConnectRateLimitedRead(
                    delayMillis = { error("delay should not run") },
                ) {
                    attempts += 1
                    throw IllegalArgumentException("invalid range")
                }
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun recognizesProviderRateLimitMessageThroughCauseChain() {
        val failure = IllegalStateException("wrapper", rateLimitFailure())

        assertEquals(true, failure.isHealthConnectRateLimitError())
    }

    private fun rateLimitFailure() = IllegalStateException(
        "Request rejected. Rate limited request quota has been exceeded. " +
            "Please wait until quota has replenished before making further requests.",
    )
}
