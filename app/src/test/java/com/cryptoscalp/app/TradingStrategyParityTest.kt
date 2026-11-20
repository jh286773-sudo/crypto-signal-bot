package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TradingStrategyParityTest {
    private fun series(count: Int, duration: Long, start: Long): List<MarketCandle> =
        (0 until count).map { i ->
            val p = 100.0 + i * 0.001
            MarketCandle(p, p + 0.2, p - 0.2, p, 100.0, start + i * duration)
        }

    @Test fun rejectsCandlesWithoutExchangeTimestamps() {
        val now = System.currentTimeMillis()
        val five = series(80, 300_000L, now - 80 * 300_000L).map { it.copy(time = 0L) }
        val fifteen = series(80, 900_000L, now - 80 * 900_000L)
        val thirty = series(80, 1_800_000L, now - 80 * 1_800_000L)
        assertEquals("WAIT", TradingStrategy.evaluate(five, fifteen, thirty).signal)
    }

    @Test fun returnsWaitWhenHigherTimeframeHistoryIsInsufficientAfterAsOfTrim() {
        val now = System.currentTimeMillis()
        val base5 = (now / 300_000L) * 300_000L - 80 * 300_000L
        val base15 = (now / 900_000L) * 900_000L - 80 * 900_000L
        val base30 = (now / 1_800_000L) * 1_800_000L - 80 * 1_800_000L
        val five = series(80, 300_000L, base5)
        val fifteen = series(60, 900_000L, base15)
        val thirty = series(60, 1_800_000L, base30)
        assertEquals("WAIT", TradingStrategy.evaluate(five, fifteen, thirty).signal)
    }
}
