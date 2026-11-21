package com.cryptoscalp.app

/**
 * Combines already-computed per-timeframe directions. It deliberately knows
 * nothing about candle timestamps or close boundaries.
 */
object TimeframeSignalAggregator {
    fun combine(selectedTimeframes: Collection<String>, signals: Map<String, String>): String {
        val selected = TimeframeSelection.normalize(selectedTimeframes)
        val directions = selected.map { signals[it] ?: "WAIT" }
        if (directions.any { it != "LONG" && it != "SHORT" }) return "WAIT"
        return if (directions.all { it == "LONG" }) "LONG"
        else if (directions.all { it == "SHORT" }) "SHORT"
        else "WAIT"
    }
}
