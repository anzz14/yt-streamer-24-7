package com.loopcast.domain

import kotlin.math.min
import kotlin.random.Random

class ReconnectPolicy(
    private val random: Random,
    private val config: Config = Config(),
) {
    data class Config(
        val baseMs: Long = Defaults.RECONNECT_BASE_MS,
        val maxMs: Long = Defaults.RECONNECT_MAX_MS,
        val jitter: Double = Defaults.RECONNECT_JITTER,
        val stableResetMs: Long = Defaults.STABLE_RESET_MS,
        val maxAttempts: Int? = null,
    )

    fun nextDelay(attempt: Int): Long? {
        if (attempt < 1 || config.maxAttempts?.let { attempt > it } == true) return null
        val exponential = config.baseMs * (1L shl min(attempt - 1, 30))
        val capped = min(exponential, config.maxMs)
        val factor = 1.0 + ((random.nextDouble() * 2.0 - 1.0) * config.jitter)
        return (capped * factor).toLong().coerceAtLeast(0)
    }

    fun shouldResetAttempts(liveDurationMs: Long): Boolean =
        liveDurationMs >= config.stableResetMs
}
