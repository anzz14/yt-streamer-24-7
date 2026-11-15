package com.loopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderProfileTest {
    private fun info(width: Int, height: Int, fps: Float? = 30f) = VideoInfo(
        width, height, 1_000, fps, "video/avc", true, "audio/mp4a-latm",
    )

    @Test
    fun selects720And1080PresetsWithoutUpscaling() {
        assertEquals(1280, chooseProfile(info(1280, 720), QualityPreference.AUTO).width)
        assertEquals(1920, chooseProfile(info(3840, 2160), QualityPreference.AUTO).width)
        assertEquals(640, chooseProfile(info(640, 480), QualityPreference.AUTO).width)
    }

    @Test
    fun handlesPortraitAndOddDimensions() {
        val portrait = chooseProfile(info(1080, 1920), QualityPreference.AUTO)
        val odd = chooseProfile(info(1281, 721), QualityPreference.AUTO)

        assertEquals(1080, portrait.width)
        assertEquals(1920, portrait.height)
        assertEquals(1280, odd.width)
        assertEquals(720, odd.height)
    }

    @Test
    fun defaultsUnknownFpsAndCapsHighFps() {
        assertEquals(30, chooseProfile(info(1280, 720, null), QualityPreference.AUTO).fps)
        assertEquals(30, chooseProfile(info(1280, 720, 60f), QualityPreference.AUTO).fps)
    }
}
