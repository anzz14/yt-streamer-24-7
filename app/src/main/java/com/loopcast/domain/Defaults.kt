package com.loopcast.domain

object Defaults {
    const val MIN_STREAM_KEY_LENGTH = 8
    const val MAX_STREAM_KEY_LENGTH = 128
    const val DEFAULT_FPS = 30
    const val MAX_FPS = 30
    const val DEFAULT_AUDIO_BITRATE_KBPS = 128
    const val DEFAULT_AUDIO_SAMPLE_RATE = 48_000
    const val KEYFRAME_INTERVAL_SECONDS = 2
    const val P720_WIDTH = 1280
    const val P720_HEIGHT = 720
    const val P720_BITRATE_KBPS = 3_000
    const val P1080_WIDTH = 1920
    const val P1080_HEIGHT = 1080
    const val P1080_BITRATE_KBPS = 4_500
    const val RECONNECT_BASE_MS = 1_000L
    const val RECONNECT_MAX_MS = 30_000L
    const val RECONNECT_JITTER = 0.2
    const val STABLE_RESET_MS = 60_000L
}
