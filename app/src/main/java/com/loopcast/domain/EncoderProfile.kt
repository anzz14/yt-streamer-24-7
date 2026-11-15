package com.loopcast.domain

data class EncoderProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val keyframeIntervalSec: Int = Defaults.KEYFRAME_INTERVAL_SECONDS,
    val audioBitrateKbps: Int = Defaults.DEFAULT_AUDIO_BITRATE_KBPS,
    val audioSampleRate: Int = Defaults.DEFAULT_AUDIO_SAMPLE_RATE,
    val stereo: Boolean = true,
)

data class VideoInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val frameRate: Float?,
    val videoMime: String,
    val hasAudio: Boolean,
    val audioMime: String? = null,
)

enum class QualityPreference { AUTO, P720, P1080 }

fun chooseProfile(info: VideoInfo, preference: QualityPreference): EncoderProfile {
    val sourceWidth = evenAtLeastOne(info.width)
    val sourceHeight = evenAtLeastOne(info.height)
    val portrait = sourceWidth < sourceHeight
    val longEdge = maxOf(sourceWidth, sourceHeight)
    val preset = when {
        preference == QualityPreference.P1080 && longEdge >= Defaults.P1080_HEIGHT ->
            Preset(Defaults.P1080_WIDTH, Defaults.P1080_HEIGHT, Defaults.P1080_BITRATE_KBPS)
        preference != QualityPreference.P720 && longEdge >= Defaults.P1080_HEIGHT ->
            Preset(Defaults.P1080_WIDTH, Defaults.P1080_HEIGHT, Defaults.P1080_BITRATE_KBPS)
        longEdge >= Defaults.P720_HEIGHT ->
            Preset(Defaults.P720_WIDTH, Defaults.P720_HEIGHT, Defaults.P720_BITRATE_KBPS)
        else -> null
    }
    val dimensions = preset?.let {
        if (portrait) it.height to it.width else it.width to it.height
    } ?: sourceWidth to sourceHeight
    val bitrate = preset?.let {
        val sourceArea = sourceWidth.toLong() * sourceHeight
        val presetArea = it.width.toLong() * it.height
        if (sourceArea >= presetArea) it.bitrate else (it.bitrate * sourceArea / presetArea).toInt()
    } ?: Defaults.P720_BITRATE_KBPS
    return EncoderProfile(
        width = dimensions.first,
        height = dimensions.second,
        fps = (info.frameRate ?: Defaults.DEFAULT_FPS.toFloat())
            .coerceAtMost(Defaults.MAX_FPS).toInt().coerceAtLeast(1),
        videoBitrateKbps = bitrate,
    )
}

private data class Preset(val width: Int, val height: Int, val bitrate: Int)

private fun evenAtLeastOne(value: Int): Int = value.coerceAtLeast(2).let { it - it % 2 }
