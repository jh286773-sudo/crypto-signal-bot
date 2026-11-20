package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalDeduplicationPolicyTest {
    @Test fun opensNewSignalOnceWhenNoPositionExists() {
        assertEquals(SignalDecision.OPEN, SignalDeduplicationPolicy.decide(null, "LONG", "WAIT", false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "LONG", "LONG", false))
    }

    @Test fun doesNotRepeatSignalWhileSameSidePositionIsOpen() {
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("LONG", "LONG", "LONG", false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("SHORT", "SHORT", "SHORT", false))
    }

    @Test fun reversesOnlyOnceAfterOppositeConfirmedSignal() {
        assertEquals(SignalDecision.REVERSE, SignalDeduplicationPolicy.decide("LONG", "SHORT", "LONG", false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("SHORT", "SHORT", "SHORT", false))
    }

    @Test fun cooldownClosesOldSideWithoutOpeningNewSide() {
        assertEquals(SignalDecision.CLOSE_ONLY, SignalDeduplicationPolicy.decide("LONG", "SHORT", "LONG", true))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "SHORT", "WAIT", true))
    }

    @Test fun waitNeverCreatesAnEntryOrReversal() {
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide(null, "WAIT", null, false))
        assertEquals(SignalDecision.NONE, SignalDeduplicationPolicy.decide("LONG", "WAIT", "LONG", false))
    }
}
