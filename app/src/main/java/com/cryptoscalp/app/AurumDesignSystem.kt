package com.cryptoscalp.app

import android.content.Context
import android.graphics.Color
import android.util.TypedValue

/**
 * Shared visual tokens for the AURUM X high-density interface.
 * All dimensions are expressed in dp/sp so Android renders crisply at the
 * device's native density instead of forcing a fake fixed 4K canvas.
 */
object AurumDesignSystem {
    val gold = Color.rgb(212, 175, 55)
    val goldHighlight = Color.rgb(245, 220, 135)
    val background = Color.rgb(8, 9, 12)
    val surface = Color.rgb(22, 23, 27)
    val surfaceRaised = Color.rgb(31, 30, 27)
    val textPrimary = Color.rgb(248, 246, 239)
    val textSecondary = Color.rgb(166, 160, 145)
    val positive = Color.rgb(54, 190, 130)
    val negative = Color.rgb(224, 91, 91)
    val border = Color.rgb(91, 76, 46)

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()

    fun horizontalGutterDp(context: Context): Int {
        val width = context.resources.configuration.screenWidthDp
        return when {
            width >= 840 -> 36
            width >= 600 -> 28
            else -> 18
        }
    }

    fun contentMaxWidthDp(context: Context): Int =
        if (context.resources.configuration.screenWidthDp >= 840) 900 else Int.MAX_VALUE
}
