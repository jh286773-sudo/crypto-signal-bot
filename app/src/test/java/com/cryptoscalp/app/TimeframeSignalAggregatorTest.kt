package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeframeSignalAggregatorTest {
    @Test fun oneSelectedTimeframeCanSignalIndependently() {
        assertEquals("LONG", TimeframeSignalAggregator.combine(listOf("1m"), mapOf("1m" to "LONG")))
        assertEquals("SHORT", TimeframeSignalAggregator.combine(listOf("4h"), mapOf("4h" to "SHORT")))
    }

    @Test fun allSelectedLongSignalsCombineToLong() {
        assertEquals("LONG", TimeframeSignalAggregator.combine(
            listOf("1m", "15m", "4h"),
            mapOf("1m" to "LONG", "15m" to "LONG", "4h" to "LONG")
        ))
    }

    @Test fun allSelectedShortSignalsCombineToShort() {
        assertEquals("SHORT", TimeframeSignalAggregator.combine(
            listOf("3m", "30m"),
            mapOf("3m" to "SHORT", "30m" to "SHORT")
        ))
    }

    @Test fun conflictingDirectionsProduceNoSignal() {
        assertEquals("WAIT", TimeframeSignalAggregator.combine(
            listOf("1m", "1h"), mapOf("1m" to "LONG", "1h" to "SHORT")
        ))
    }

    @Test fun anySelectedTimeframeWithoutSignalBlocksFinalSignal() {
        assertEquals("WAIT", TimeframeSignalAggregator.combine(
            listOf("5m", "4h"), mapOf("5m" to "LONG", "4h" to "WAIT")
        ))
    }

    @Test fun unselectedTimeframesCannotInfluenceResult() {
        assertEquals("LONG", TimeframeSignalAggregator.combine(
            listOf("1m"), mapOf("1m" to "LONG", "4h" to "SHORT")
        ))
    }
}
