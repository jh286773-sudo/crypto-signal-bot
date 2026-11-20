package com.cryptoscalp.app

enum class SignalDecision { NONE, OPEN, CLOSE_ONLY }

/**
 * LONG-only position policy:
 * - LONG opens a position whenever flat.
 * - SHORT only closes an existing LONG; it never opens a SHORT.
 */
object SignalDeduplicationPolicy {
    fun decide(
        openSide: String?,
        signal: String,
        lastSignal: String?
    ): SignalDecision {
        if (signal != "LONG" && signal != "SHORT") return SignalDecision.NONE

        if (openSide == "LONG") {
            return if (signal == "SHORT") SignalDecision.CLOSE_ONLY else SignalDecision.NONE
        }

        // Clean up legacy SHORT positions when a LONG arrives, but never create
        // another SHORT position. A fresh LONG may be opened on a later candle.
        if (openSide == "SHORT") {
            return SignalDecision.CLOSE_ONLY
        }

        if (openSide != null) return SignalDecision.NONE
        if (signal != "LONG") return SignalDecision.NONE
        return SignalDecision.OPEN
    }
}
