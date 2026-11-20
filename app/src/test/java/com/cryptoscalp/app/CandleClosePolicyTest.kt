package com.cryptoscalp.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleClosePolicyTest {
    @Test fun excludesCandleOneMillisecondBeforeClose() {
        assertFalse(CandleClosePolicy.isClosed(1_800_000L, 300_000L, 2_099_999L))
    }

    @Test fun includesCandleExactlyAtCloseBoundary() {
        assertTrue(CandleClosePolicy.isClosed(1_800_000L, 300_000L, 2_100_000L))
    }

    @Test fun includesCandleAfterCloseBoundary() {
        assertTrue(CandleClosePolicy.isClosed(1_800_000L, 300_000L, 2_100_001L))
    }

    @Test fun rejectsInvalidTimestampOrDuration() {
        assertFalse(CandleClosePolicy.isClosed(0L, 300_000L, 2_100_000L))
        assertFalse(CandleClosePolicy.isClosed(1_800_000L, 0L, 2_100_000L))
    }
}
