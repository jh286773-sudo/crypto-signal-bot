package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeframeSelectionTest {
    @Test fun supportsAllSevenTimeframesInStableOrder() {
        assertEquals(
            listOf("1m", "3m", "5m", "15m", "30m", "1h", "4h"),
            TimeframeSelection.normalize(listOf("4h", "1m", "30m", "3m", "1h", "15m", "5m"))
        )
    }

    @Test fun allowsAnySingleTimeframe() {
        TimeframeSelection.supported.forEach { tf ->
            assertEquals(listOf(tf), TimeframeSelection.normalize(listOf(tf)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptySelectionInsteadOfInventingDefaults() {
        TimeframeSelection.normalize(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedTimeframes() {
        TimeframeSelection.normalize(listOf("2m"))
    }

    @Test fun closedCandleFilterUsesOnlyItsOwnTimeframe() {
        val candles = listOf(
            MarketCandle(1.0, 1.0, 1.0, 1.0, time = 60_000L),
            MarketCandle(1.0, 1.0, 1.0, 1.0, time = 120_000L)
        )
        val closed = TimeframeSelection.latestClosed(candles, "1m", 180_000L)
        assertEquals(listOf(60_000L, 120_000L), closed.map { it.time })
    }

    @Test fun selectedDataDropsUnselectedTimeframes() {
        val one = listOf(MarketCandle(1.0, 1.0, 1.0, 1.0, time = 60_000L))
        val result = TimeframeSelection.selectedData(mapOf("1m" to one, "4h" to one), listOf("1m"))
        assertEquals(setOf("1m"), result.keys)
        assertFalse(result.containsKey("4h"))
        assertTrue(result.containsKey("1m"))
    }
}
