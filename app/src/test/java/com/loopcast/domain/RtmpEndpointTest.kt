package com.loopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpEndpointTest {
    private val key = "abcd-efgh-ijkl-mnop"

    @Test
    fun parsesTypedKeyAndBuildsFullUrl() {
        val endpoint = RtmpEndpoint.parseFromUserInput(RtmpEndpoint.YOUTUBE_RTMPS, key).getOrThrow()

        assertEquals("${RtmpEndpoint.YOUTUBE_RTMPS}/$key", endpoint.fullUrl())
        assertFalse(endpoint.toString().contains(key))
    }

    @Test
    fun splitsPastedFullUrl() {
        val endpoint = RtmpEndpoint.parseFromUserInput(
            "${RtmpEndpoint.YOUTUBE_RTMPS}/$key",
            "",
        ).getOrThrow()

        assertEquals(RtmpEndpoint.YOUTUBE_RTMPS, endpoint.baseUrl)
        assertEquals(key, endpoint.key.reveal())
    }

    @Test
    fun keepsBackupQueryOnBaseUrl() {
        val endpoint = RtmpEndpoint.parseFromUserInput(
            "${RtmpEndpoint.YOUTUBE_BACKUP_RTMPS}/$key",
            "",
        ).getOrThrow()

        assertEquals(RtmpEndpoint.YOUTUBE_BACKUP_RTMPS, endpoint.baseUrl)
        assertEquals("${RtmpEndpoint.YOUTUBE_BACKUP_RTMPS}/$key", endpoint.fullUrl())
    }

    @Test
    fun reportsInputFailures() {
        assertTrue(RtmpEndpoint.parseFromUserInput("https://example.com/live", key)
            .exceptionOrNull() is InvalidScheme)
        assertTrue(RtmpEndpoint.parseFromUserInput(RtmpEndpoint.YOUTUBE_RTMP, "")
            .exceptionOrNull() is MissingKey)
        assertTrue(RtmpEndpoint.parseFromUserInput(RtmpEndpoint.YOUTUBE_RTMP, "bad")
            .exceptionOrNull() is InvalidKey)
        assertTrue(RtmpEndpoint.parseFromUserInput("rtmp://", key)
            .exceptionOrNull() is MalformedUrl)
    }
}
