package one.aozora.darkhour.data

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import kotlinx.coroutines.delay
import java.util.Locale

internal data class HealthConnectReadRetryPolicy(
    val maxAttempts: Int = 6,
    val initialDelayMillis: Long = 1_000L,
    val maximumDelayMillis: Long = 16_000L,
) {
    init {
        require(maxAttempts >= 1)
        require(initialDelayMillis >= 0L)
        require(maximumDelayMillis >= initialDelayMillis)
    }
}

internal suspend fun <T : Record> HealthConnectClient.readRecordsWithRateLimitRetry(
    request: ReadRecordsRequest<T>,
): ReadRecordsResponse<T> = retryHealthConnectRateLimitedRead {
    readRecords(request)
}

internal suspend fun <T> retryHealthConnectRateLimitedRead(
    policy: HealthConnectReadRetryPolicy = HealthConnectReadRetryPolicy(),
    delayMillis: suspend (Long) -> Unit = { delay(it) },
    read: suspend () -> T,
): T {
    var attempt = 1
    var nextDelayMillis = policy.initialDelayMillis
    while (true) {
        try {
            return read()
        } catch (failure: Exception) {
            if (!failure.isHealthConnectRateLimitError() || attempt >= policy.maxAttempts) {
                throw failure
            }
            delayMillis(nextDelayMillis)
            attempt += 1
            nextDelayMillis = (nextDelayMillis * 2L)
                .coerceAtMost(policy.maximumDelayMillis)
        }
    }
}

internal fun Throwable.isHealthConnectRateLimitError(): Boolean {
    var failure: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        val message = failure?.message?.lowercase(Locale.ROOT).orEmpty()
        if (RATE_LIMIT_MARKERS.any(message::contains)) return true
        val cause = failure?.cause
        if (cause == null || cause === failure) return false
        failure = cause
    }
    return false
}

private val RATE_LIMIT_MARKERS = listOf(
    "rate limit",
    "rate-limit",
    "rate_limit",
    "quota has been exceeded",
    "quota exceeded",
)

private const val MAX_CAUSE_DEPTH = 8
