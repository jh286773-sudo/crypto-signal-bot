package com.cryptoscalp.app

import java.util.Locale

/** Stable, locale-independent formatting for the user's compact signal notifications. */
internal object SignalNotificationFormatter {
    fun format(price: Double, signal: String): String {
        require(signal == "LONG" || signal == "SHORT") { "Only directional signals can be notified." }
        require(price.isFinite() && price > 0.0) { "Entry price must be finite and positive." }
        return "${formatPrice(price)} ورود $signal"
    }

    fun formatPrice(price: Double): String {
        require(price.isFinite() && price > 0.0) { "Price must be finite and positive." }
        return when {
            price >= 100.0 -> String.format(Locale.US, "%.2f", price)
            price >= 1.0 -> String.format(Locale.US, "%.4f", price)
            else -> String.format(Locale.US, "%.7f", price)
        }
    }
}
