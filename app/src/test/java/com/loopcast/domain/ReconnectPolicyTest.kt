package com.loopcast.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun exponentialDelayCapsWithoutJitter() {
        val policy = ReconnectPolicy(Random(1), ReconnectPolicy.Config(jitter = 0.0))

        assertEquals(1_000, policy.nextDelay(1))
        assertEquals(2_000, policy.nextDelay(2))
        assertEquals(30_000, policy.nextDelay(8))
    }

    @Test
    fun appliesJitterWithinConfiguredBounds() {
        val policy = ReconnectPolicy(Random(4), ReconnectPolicy.Config(jitter = 0.2))
        val delay = checkNotNull(policy.nextDelay(1))

        assertTrue(delay in 800..1_200)
    }

    @Test
    fun respectsAttemptLimitAndStableReset() {
        val policy = ReconnectPolicy(
            Random(1),
            ReconnectPolicy.Config(maxAttempts = 2, stableResetMs = 60_000),
        )

        assertEquals(null, policy.nextDelay(3))
        assertTrue(policy.shouldResetAttempts(60_000))
    }
}
