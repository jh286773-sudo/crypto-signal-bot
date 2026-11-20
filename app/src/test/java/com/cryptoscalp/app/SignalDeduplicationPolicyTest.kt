package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalDeduplicationPolicyTest {
    @Test fun opensLongWheneverFlat() {
        assertEquals(SignalDecision.OPEN, SignalDeduplicationPolicy.decide(null, "LONG", "WAIT"))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "SHORT", "WAIT"))
        assertEquals(SignalDecision.OPEN, SignalDeduplicationPolicy.decide(null, "LONG", "LONG"))
        assertEquals(SignalDecision.OPEN, SignalDeduplicationPolicy.decide(null, "LONG", "WAIT"))
    }

    @Test fun shortOnlyClosesAnExistingLong() {
        assertEquals(SignalDecision.CLOSE_ONLY, SignalDeduplicationPolicy.decide("LONG", "SHORT", "LONG"))
        assertEquals(SignalDecision.CLOSE_ONLY, SignalDeduplicationPolicy.decide("LONG", "SHORT", "LONG"))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "SHORT", "WAIT"))
    }

    @Test fun sameSideLongDoesNotDuplicateOrResetPosition() {
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("LONG", "LONG", "LONG"))
    }

    @Test fun legacyShortPositionIsNeverReversedIntoAnotherShort() {
        assertEquals(SignalDecision.CLOSE_ONLY, SignalDeduplicationPolicy.decide("SHORT", "LONG", "SHORT"))
        assertEquals(SignalDecision.CLOSE_ONLY, SignalDeduplicationPolicy.decide("SHORT", "SHORT", "SHORT"))
    }

    @Test fun waitNeverCreatesAnEntryOrExit() {
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "WAIT", null))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("LONG", "WAIT", "LONG"))
    }
}
