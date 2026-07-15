package one.aozora.darkhour.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthConnectPagingTest {
    @Test
    fun treatsMissingOrBlankTokensAsTerminal() {
        val seenPageTokens = mutableSetOf<String>()

        assertNull(nextHealthConnectPageToken(null, seenPageTokens))
        assertNull(nextHealthConnectPageToken("", seenPageTokens))
        assertNull(nextHealthConnectPageToken("   ", seenPageTokens))
        assertEquals(emptySet<String>(), seenPageTokens)
    }

    @Test
    fun preservesAndTracksNonBlankToken() {
        val seenPageTokens = mutableSetOf<String>()

        assertEquals("next-page", nextHealthConnectPageToken("next-page", seenPageTokens))
        assertEquals(setOf("next-page"), seenPageTokens)
    }

    @Test
    fun rejectsRepeatedToken() {
        val seenPageTokens = mutableSetOf("next-page")

        val failure = assertThrows(IllegalStateException::class.java) {
            nextHealthConnectPageToken("next-page", seenPageTokens)
        }

        assertEquals("Health Connect returned a repeated page token", failure.message)
    }
}
