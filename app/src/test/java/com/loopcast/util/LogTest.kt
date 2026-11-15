package com.loopcast.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogTest {
    @Test
    fun redactMasksStreamKeyInUrl() {
        val input = "rtmps://a.rtmp.youtube.com:443/live2/abcd-efgh-ijkl-mnop-qrst"

        assertEquals(
            "rtmps://a.rtmp.youtube.com:443/live2/****",
            Log.redact(input),
        )
    }

    @Test
    fun redactLeavesNormalTextUnchanged() {
        assertEquals("stream started", Log.redact("stream started"))
    }
}
