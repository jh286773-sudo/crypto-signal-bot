package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class TradingStrategyParityTest {
    private fun series(count:Int,duration:Long,start:Long):List<MarketCandle> =
        (0 until count).map { i ->
            val p=100.0+i*0.001
            MarketCandle(p,p+0.2,p-0.2,p,100.0,start+i*duration)
        }

    @Test fun directIndicatorRulesHaveNoWeightsOrScoreThresholds() {
        assertEquals("bullish",TradingStrategy.indicatorBias(mapOf(
            "EMA" to 1,"RSI" to 1,"MACD" to 1,"ADX_DMI" to 1,
            "VWAP" to 1,"OBV_Trend" to 0,"Bollinger" to 1)))
        assertEquals("bearish",TradingStrategy.indicatorBias(mapOf(
            "EMA" to -1,"RSI" to -1,"MACD" to -1,"ADX_DMI" to -1,
            "VWAP" to -1,"OBV_Trend" to 0,"Bollinger" to -1)))
        assertEquals("bullish",TradingStrategy.indicatorBias(mapOf(
            "EMA" to 1,"RSI" to 1,"MACD" to 1,"ADX_DMI" to 1,
            "VWAP" to -1,"OBV_Trend" to -1,"Bollinger" to -1)))
        assertEquals("bearish",TradingStrategy.indicatorBias(mapOf(
            "EMA" to -1,"RSI" to 0,"MACD" to -1,"ADX_DMI" to -1)))
        assertEquals("neutral",TradingStrategy.indicatorBias(mapOf(
            "EMA" to 1,"RSI" to 0,"MACD" to 0,"ADX_DMI" to 1)))
    }

    @Test fun oneSelectedTimeframeIsAnalyzedWithoutRequiringOtherTimeframes() {
        val now=System.currentTimeMillis()
        val duration=60_000L
        val start=(now/duration)*duration-219*duration
        val rows=series(220,duration,start)
        val result=TradingStrategy.evaluate(mapOf("1m" to rows),rows.last().time+duration+1_000L)
        assertEquals("WAIT",result.signal) // monotonic fixture has an overbought RSI, so no direction
        assertTrue(result.reasons.any{it.contains("تحلیل اندیکاتورها")})
        assertFalse(result.reasons.any{it.contains("5m")||it.contains("15m")||it.contains("30m")})
    }

    @Test fun twoAndFourSelectedTimeframesAreAcceptedAsTheSelection() {
        val now=System.currentTimeMillis()
        val durations=mapOf("1m" to 60_000L,"3m" to 180_000L,
            "15m" to 900_000L,"4h" to 14_400_000L)
        val decision=(now/14_400_000L)*14_400_000L
        val selected=durations.mapValues { (_,step) ->
            val lastOpen=decision-step
            series(220,step,lastOpen-219L*step)
        }
        val result=TradingStrategy.evaluate(selected,decision+1_000L)
        assertFalse(result.reasons.any{
            it.contains("هیچ تایم‌فریمی") ||
            it.contains("داده تایم‌فریم انتخاب‌شده موجود نیست") ||
            it.contains("اطلاعات کافی/معتبر نیست")
        })
    }

    @Test fun missingSelectedSeriesReturnsWait() {
        val now=System.currentTimeMillis()
        val start=(now/60_000L)*60_000L-219*60_000L
        val result=TradingStrategy.evaluate(mapOf(
            "1m" to series(220,60_000L,start),
            "4h" to emptyList()
        ),now)
        assertEquals("WAIT",result.signal)
        assertTrue(result.reasons.first().contains("داده تایم‌فریم انتخاب‌شده موجود نیست"))
    }

    @Test fun invalidHistoricalCandleIsRejected() {
        val now=System.currentTimeMillis()
        val duration=60_000L
        val start=(now/duration)*duration-219*duration
        val rows=series(220,duration,start).toMutableList()
        rows[0]=rows[0].copy(high=rows[0].open-1.0)
        val result=TradingStrategy.evaluate(mapOf("1m" to rows),now)
        assertEquals("WAIT",result.signal)
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test fun selectedTimeframesNeedClosedCandlesAtCommonBoundary() {
        val now=System.currentTimeMillis()
        val duration=300_000L
        val start=(now/duration)*duration-219*duration
        val rows=series(220,duration,start)
        val beforeClose=TradingStrategy.evaluate(mapOf("5m" to rows),rows.last().time+duration-1)
        assertEquals("WAIT",beforeClose.signal)
        assertTrue(beforeClose.reasons.first().contains("کندل بسته‌شده تازه"))
    }
}
