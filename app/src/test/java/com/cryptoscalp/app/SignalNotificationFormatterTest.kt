package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalNotificationFormatterTest {
    @Test fun formatsShortWithPriceFirst() {
        assertEquals("2480.00 ورود SHORT", SignalNotificationFormatter.format(2480.0, "SHORT"))
    }

    @Test fun formatsLongWithPriceFirst() {
        assertEquals("2500.00 ورود LONG", SignalNotificationFormatter.format(2500.0, "LONG"))
    }

    @Test fun preservesSmallPricePrecision() {
        assertEquals("0.0123456 ورود LONG", SignalNotificationFormatter.format(0.0123456, "LONG"))
    }

    @Test fun rejectsNonDirectionalSignalsAndInvalidPrices() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalNotificationFormatter.format(10.0, "WAIT")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalNotificationFormatter.format(Double.NaN, "LONG")
        }
    }
}
