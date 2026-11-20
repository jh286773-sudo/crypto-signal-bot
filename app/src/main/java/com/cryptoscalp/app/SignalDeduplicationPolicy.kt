package com.cryptoscalp.app

enum class SignalDecision { NONE, OPEN, REVERSE, CLOSE_ONLY }

/** Pure decision policy for one notification per directional setup. */
object SignalDeduplicationPolicy {
    fun decide(
        openSide: String?,
        signal: String,
        lastSignal: String?,
        cooldownActive: Boolean
    ): SignalDecision {
        if (signal != "LONG" && signal != "SHORT") return SignalDecision.NONE
        if (openSide == null) {
            if (cooldownActive || lastSignal == signal) return SignalDecision.NONE
            return SignalDecision.OPEN
        }
        if (openSide != "LONG" && openSide != "SHORT") return SignalDecision.NONE
        if (openSide == signal) return SignalDecision.NONE
        return if (cooldownActive) SignalDecision.CLOSE_ONLY else SignalDecision.REVERSE
    }
}
