package com.loopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamKeyTest {
    @Test
    fun parseTrimsAndMasksKey() {
        val result = StreamKey.parse("  abcd-efgh  ").getOrThrow()

        assertEquals("abcd-efgh", result.reveal())
        assertEquals("••••-efgh", result.toString())
    }

    @Test
    fun parseRejectsBlankWhitespaceAndInvalidLength() {
        assertTrue(StreamKey.parse("   ").isFailure)
        assertTrue(StreamKey.parse("abcd efgh").isFailure)
        assertTrue(StreamKey.parse("short").isFailure)
    }
}
