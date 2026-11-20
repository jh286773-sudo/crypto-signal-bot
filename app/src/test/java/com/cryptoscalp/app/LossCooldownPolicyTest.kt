package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LossCooldownPolicyTest {
    @Test fun secondFullStopWithin24HoursStarts24HourCooldown() {
        val now = 1_800_000_000_000L
        val first = LossCooldownPolicy.recordFullStop(emptyList(), now)
        assertEquals(listOf(now), first.recentLosses)
        assertEquals(0L, first.cooldownUntilMs)

        val second = LossCooldownPolicy.recordFullStop(first.recentLosses, now + 2 * 60 * 60 * 1000L)
        assertTrue(second.recentLosses.isEmpty())
        assertEquals(now + 26 * 60 * 60 * 1000L, second.cooldownUntilMs)
        assertTrue(LossCooldownPolicy.isActive(second.cooldownUntilMs, now))
        assertFalse(LossCooldownPolicy.isActive(second.cooldownUntilMs, second.cooldownUntilMs))
    }

    @Test fun aStopOutsideTheRolling24HourWindowDoesNotTriggerCooldown() {
        val now = 1_800_000_000_000L
        val oldLoss = now - LossCooldownPolicy.LOSS_WINDOW_MS - 1
        val result = LossCooldownPolicy.recordFullStop(listOf(oldLoss), now)
        assertEquals(listOf(now), result.recentLosses)
        assertEquals(0L, result.cooldownUntilMs)
    }
}
