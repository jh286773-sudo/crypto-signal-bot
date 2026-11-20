package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

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

    @Test fun sharedGoldenFixtureProducesSameNoSignalDecisionAsPython() {
        // Android's local JVM test runtime provides a stub org.json implementation
        // that throws "Method ... not mocked". Read this tiny shared fixture with
        // java.util.regex so the test exercises the real strategy implementation.
        val raw = javaClass.getResourceAsStream("/strategy_parity_fixture.json")!!
            .bufferedReader().use { it.readText() }
        fun number(key: String): Long =
            Regex("\\\"$key\\\"\\s*:\\s*(\\d+)")
                .find(raw)?.groupValues?.get(1)?.toLong()
                ?: error("Missing numeric fixture field: $key")
        fun expected(key: String): String =
            Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(raw)?.groupValues?.get(1)
                ?: error("Missing string fixture field: $key")

        val start = number("start_ms")
        val base = Regex("\\\"base_price\\\"\\s*:\\s*([0-9.]+)")
            .find(raw)?.groupValues?.get(1)?.toDouble() ?: error("Missing base_price")
        fun candles(tf: String, duration: Long): List<MarketCandle> {
            val count = number(tf).toInt()
            val alignedStart = (start / duration) * duration
            return (0 until count).map { i ->
                val close = base + i * 0.02 + sin(i * 0.3) * 0.03
                MarketCandle(close - 0.02, close + 0.15, close - 0.15,
                    close, 100.0 + i, alignedStart + i * duration)
            }
        }
        val five = candles("5m", 300_000L)
        val fifteen = candles("15m", 900_000L)
        val thirty = candles("30m", 1_800_000L)
        val decisionTime = five.last().time + 300_000L
        val result = TradingStrategy.evaluate(five, fifteen, thirty, decisionTime + 1_000L)
        val normalized = if (result.signal == "WAIT") "NO_SIGNAL" else result.signal
        assertEquals(expected("expected_signal"), normalized)
        assertEquals(number("expected_score").toInt(), result.score)
    }

    @Test fun rejectsTakerBuyVolumeAboveTotalVolume() {
        val now = System.currentTimeMillis()
        val fiveStart = (now / 300_000L) * 300_000L - 80 * 300_000L
        val fifteenStart = (now / 900_000L) * 900_000L - 80 * 900_000L
        val thirtyStart = (now / 1_800_000L) * 1_800_000L - 80 * 1_800_000L
        val five = series(80, 300_000L, fiveStart).toMutableList()
        five[0] = five[0].copy(takerBuyVolume = five[0].volume + 1.0)
        val result = TradingStrategy.evaluate(
            five, series(80, 900_000L, fifteenStart), series(80, 1_800_000L, thirtyStart)
        )
        assertEquals("WAIT", result.signal)
        assert(result.reasons.any { it.contains("داده معتبر کافی نیست") })
    }

    @Test fun usesExchangeAsOfTimeToRejectAnUnclosedFiveMinuteCandle() {
        val now = System.currentTimeMillis()
        val fiveStart = (now / 300_000L) * 300_000L - 79 * 300_000L
        val fifteenStart = (now / 900_000L) * 900_000L - 79 * 900_000L
        val thirtyStart = (now / 1_800_000L) * 1_800_000L - 79 * 1_800_000L
        val five = series(80, 300_000L, fiveStart)
        val fifteen = series(80, 900_000L, fifteenStart)
        val thirty = series(80, 1_800_000L, thirtyStart)
        val decisionTime = five.last().time + 300_000L
        val result = TradingStrategy.evaluate(five, fifteen, thirty, decisionTime - 1L)
        assertEquals("WAIT", result.signal)
        assert(result.reasons.any { it.contains("قابل‌همگام‌سازی نیست") })
    }

    @Test fun rejectsMalformedOldCandleBeforeRecursiveIndicators() {
        val now = System.currentTimeMillis()
        val base5 = (now / 300_000L) * 300_000L - 80 * 300_000L
        val base15 = (now / 900_000L) * 900_000L - 80 * 900_000L
        val base30 = (now / 1_800_000L) * 1_800_000L - 80 * 1_800_000L
        val five = series(80, 300_000L, base5).toMutableList()
        five[0] = five[0].copy(high = five[0].open - 1.0)
        val fifteen = series(80, 900_000L, base15)
        val thirty = series(80, 1_800_000L, base30)
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
    @Test fun rejectsHigherTimeframeSeriesNotAlignedToFiveMinuteDecisionBoundary() {
        val now = System.currentTimeMillis()
        val fiveStart = (now / 300_000L) * 300_000L - 80 * 300_000L
        val five = series(80, 300_000L, fiveStart)
        val decisionTime = five.last().time + 300_000L
        val expected15 = (decisionTime / 900_000L) * 900_000L - 900_000L
        val expected30 = (decisionTime / 1_800_000L) * 1_800_000L - 1_800_000L
        val fifteen = series(80, 900_000L, expected15 - 80 * 900_000L)
        val thirty = series(80, 1_800_000L, expected30 - 79 * 1_800_000L)
        val result = TradingStrategy.evaluate(five, fifteen, thirty, decisionTime + 1_000L)
        assertEquals("WAIT", result.signal)
        assert(result.reasons.any { it.contains("همگام نیست") })
    }

    @Test fun ema21AntiChaseGateRejectsExtendedOrMissingDataAndAcceptsPullback() {
        assertEquals(true, TradingStrategy.entryNotExtended(100.0, 99.5, 1.0))
        assertEquals(false, TradingStrategy.entryNotExtended(100.0, 99.0, 1.0))
        assertEquals(false, TradingStrategy.entryNotExtended(100.0, null, 1.0))
        assertEquals(false, TradingStrategy.entryNotExtended(100.0, 99.5, null))
        assertEquals(false, TradingStrategy.entryNotExtended(100.0, 99.5, 0.0))
    }

}
