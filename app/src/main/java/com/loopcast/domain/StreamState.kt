package com.loopcast.domain

sealed interface StreamState {
    data class Idle(val source: VideoInfo? = null) : StreamState
    data class Preparing(val source: VideoInfo? = null) : StreamState
    data class Ready(val source: VideoInfo? = null) : StreamState
    data class Connecting(val attempt: Int, val source: VideoInfo? = null) : StreamState
    data class Live(val sinceMs: Long, val source: VideoInfo? = null) : StreamState
    data class Reconnecting(
        val attempt: Int,
        val nextDelayMs: Long,
        val reason: StreamError,
        val source: VideoInfo? = null,
    ) : StreamState
    data class Stopping(val source: VideoInfo? = null) : StreamState
    data class Failed(val error: StreamError, val source: VideoInfo? = null) : StreamState
}

sealed interface StreamEvent {
    data class SourceSelected(val info: VideoInfo) : StreamEvent
    data object SourceCleared : StreamEvent
    data object StartRequested : StreamEvent
    data object PrepareOk : StreamEvent
    data object ConnectRequested : StreamEvent
    data class PrepareFailed(val error: StreamError) : StreamEvent
    data class ConnectOk(val nowMs: Long) : StreamEvent
    data class ConnectFailed(val error: StreamError) : StreamEvent
    data class Dropped(val error: StreamError) : StreamEvent
    data class RetryScheduled(val attempt: Int, val delayMs: Long) : StreamEvent
    data object StopRequested : StreamEvent
    data object Stopped : StreamEvent
    data object Reset : StreamEvent
}

data class Transition(val state: StreamState, val ignored: Boolean = false)

fun reduce(state: StreamState, event: StreamEvent): Transition {
    return when (event) {
        is StreamEvent.SourceSelected -> Transition(StreamState.Idle(event.info))
        StreamEvent.SourceCleared -> if (state is StreamState.Idle) {
            Transition(StreamState.Idle())
        } else {
            ignored(state)
        }
        StreamEvent.StartRequested -> when (state) {
            is StreamState.Idle -> state.source?.let { Transition(StreamState.Preparing(it)) } ?: ignored(state)
            is StreamState.Failed -> Transition(StreamState.Preparing(state.source))
            else -> ignored(state)
        }
        StreamEvent.PrepareOk -> when (state) {
            is StreamState.Preparing -> Transition(StreamState.Ready(state.source))
            else -> ignored(state)
        }
        StreamEvent.ConnectRequested -> when (state) {
            is StreamState.Ready -> Transition(StreamState.Connecting(1, state.source))
            is StreamState.Reconnecting -> Transition(StreamState.Connecting(state.attempt, state.source))
            else -> ignored(state)
        }
        is StreamEvent.PrepareFailed -> when (state) {
            is StreamState.Preparing -> Transition(StreamState.Failed(event.error, state.source))
            else -> ignored(state)
        }
        is StreamEvent.ConnectOk -> when (state) {
            is StreamState.Connecting -> Transition(StreamState.Live(event.nowMs, state.source))
            else -> ignored(state)
        }
        is StreamEvent.ConnectFailed -> when (state) {
            is StreamState.Connecting -> if (event.error.retryable) {
                Transition(StreamState.Reconnecting(state.attempt + 1, 0, event.error, state.source))
            } else {
                Transition(StreamState.Failed(event.error, state.source))
            }
            else -> ignored(state)
        }
        is StreamEvent.Dropped -> when (state) {
            is StreamState.Live -> Transition(StreamState.Reconnecting(1, 0, event.error, state.source))
            else -> ignored(state)
        }
        is StreamEvent.RetryScheduled -> when (state) {
            is StreamState.Reconnecting -> Transition(
                StreamState.Reconnecting(event.attempt, event.delayMs, state.reason, state.source),
            )
            else -> ignored(state)
        }
        StreamEvent.StopRequested -> when (state) {
            is StreamState.Preparing -> Transition(StreamState.Stopping(state.source))
            is StreamState.Ready -> Transition(StreamState.Stopping(state.source))
            is StreamState.Connecting -> Transition(StreamState.Stopping(state.source))
            is StreamState.Live -> Transition(StreamState.Stopping(state.source))
            is StreamState.Reconnecting -> Transition(StreamState.Stopping(state.source))
            else -> ignored(state)
        }
        StreamEvent.Stopped -> when (state) {
            is StreamState.Stopping -> Transition(StreamState.Idle(state.source))
            else -> ignored(state)
        }
        StreamEvent.Reset -> Transition(StreamState.Idle())
    }
}

private fun ignored(state: StreamState) = Transition(state, ignored = true)
