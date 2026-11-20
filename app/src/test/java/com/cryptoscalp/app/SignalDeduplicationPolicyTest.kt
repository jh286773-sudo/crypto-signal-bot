package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalDeduplicationPolicyTest {
    @Test fun opensLongOnlyWhenFlatAndNotCoolingDown() {
        assertEquals(SignalDecision.OPEN, SignalDeduplicationPolicy.decide(null, "LONG", "WAIT", false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "SHORT", "WAIT", false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "LONG", "LONG", false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "LONG", "WAIT", true))
    }

    @Test fun shortOnlyClosesAnExistingLong() {
        assertEquals(SignalDecision.CLOSE_ONLY, SignalDeduplicationPolicy.decide("LONG", "SHORT", "LONG", false))
        assertEquals(SignalDecision.CLOSE_ONLY, SignalDeduplicationPolicy.decide("LONG", "SHORT", "LONG", true))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "SHORT", "WAIT", false))
    }

    @Test fun sameSideLongDoesNotDuplicateOrResetPosition() {
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("LONG", "LONG", "LONG", false))
    }

    @Test fun legacyShortPositionIsNeverReversedIntoAnotherShort() {
        assertEquals(SignalDecision.CLOSE_ONLY, SignalDeduplicationPolicy.decide("SHORT", "LONG", "SHORT", false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("SHORT", "SHORT", "SHORT", false))
    }

    @Test fun waitNeverCreatesAnEntryOrExit() {
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "WAIT", null, false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("LONG", "WAIT", "LONG", false))
    }
}
