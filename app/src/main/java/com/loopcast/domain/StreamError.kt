package com.loopcast.domain

sealed class StreamError(
    val retryable: Boolean,
    val userMessage: String,
) {
    data object InvalidKey : StreamError(false, "The stream key is invalid.")
    data object AuthFailed : StreamError(false, "YouTube rejected the stream key.")
    data object Network : StreamError(true, "The network connection was lost.")
    data object ServerRejected : StreamError(true, "The streaming server rejected the connection.")
    data object UnsupportedMedia : StreamError(false, "This video format is not supported.")
    data object EncoderInit : StreamError(true, "The video encoder could not start.")
    data object Thermal : StreamError(true, "The phone is too hot to continue streaming.")
    data class Unknown(val detail: String) : StreamError(true, "Streaming stopped unexpectedly.")
}
