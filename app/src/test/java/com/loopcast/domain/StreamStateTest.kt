package com.loopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStateTest {
    private val source = VideoInfo(1280, 720, 1_000, 30f, "video/avc", true)

    @Test
    fun followsHappyPathAndKeepsSourceWhenStopped() {
        var state: StreamState = StreamState.Idle(source)
        state = reduce(state, StreamEvent.StartRequested).state
        state = reduce(state, StreamEvent.PrepareOk).state
        state = reduce(state, StreamEvent.ConnectRequested).state
        state = reduce(state, StreamEvent.ConnectOk(100)).state
        assertTrue(state is StreamState.Live)
        state = reduce(state, StreamEvent.StopRequested).state
        state = reduce(state, StreamEvent.Stopped).state
        assertEquals(source, (state as StreamState.Idle).source)
    }

    @Test
    fun rejectsMissingSourceAndNonRetryableConnectFailure() {
        val idle = StreamState.Idle()
        val start = reduce(idle, StreamEvent.StartRequested)
        assertTrue(start.ignored)
        val connecting = StreamState.Connecting(2, source)
        val failed = reduce(connecting, StreamEvent.ConnectFailed(StreamError.AuthFailed))
        assertTrue(failed.state is StreamState.Failed)
        assertFalse(failed.ignored)
    }

    @Test
    fun droppedLiveStateSchedulesRetry() {
        val live = StreamState.Live(10, source)
        val transition = reduce(live, StreamEvent.Dropped(StreamError.Network))

        assertEquals(1, (transition.state as StreamState.Reconnecting).attempt)
    }
}
