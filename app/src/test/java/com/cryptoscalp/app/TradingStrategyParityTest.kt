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

    private fun fallingSeries(count:Int,duration:Long,start:Long):List<MarketCandle> =
        (0 until count).map { i ->
            val p=100.0-i*0.001
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
        assertEquals("LONG",result.signal) // RSI above 50 confirms the rising trend even when overbought
        assertTrue(result.reasons.any{it.startsWith("1m:")})
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

    @Test fun exceptionalVolatilityBlocksAndroidEntry() {
        val now=System.currentTimeMillis()
        val duration=60_000L
        val start=(now/duration)*duration-219*duration
        val rows=series(220,duration,start).toMutableList()
        // Large final range expands ATR sharply relative to the preceding baseline.
        val last=rows.last()
        rows[rows.lastIndex]=last.copy(high=last.close+30.0,low=last.close-30.0)
        val result=TradingStrategy.evaluate(mapOf("1m" to rows),rows.last().time+duration+1_000L)
        assertEquals("WAIT",result.signal)
        assertTrue(result.reasons.any{it.contains("نوسان غیرعادی")})
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

    @Test fun selectedTimeframesDoNotNeedASharedCandleBoundary() {
        val duration1m=60_000L
        val duration3m=180_000L
        val duration15m=900_000L
        val duration4h=14_400_000L
        val decision=(System.currentTimeMillis()/duration4h)*duration4h
        val rows1m=series(220,duration1m,decision-219*duration1m)
        val rows3m=series(220,duration3m,decision-219*duration3m-duration3m)
        val rows15m=series(220,duration15m,decision-219*duration15m-duration15m)
        val rows4h=series(220,duration4h,decision-220*duration4h)
        // The 1m series ends later than the other timeframes; all are independently fresh.
        val asOf=decision+duration1m
        val result=TradingStrategy.evaluate(mapOf(
            "1m" to rows1m,
            "3m" to rows3m,
            "15m" to rows15m,
            "4h" to rows4h
        ),asOf)
        assertFalse(result.reasons.any{it.contains("مرز تصمیم مشترک")||it.contains("کندل بسته‌شده تازه برای تایم‌فریم‌های انتخاب‌شده")})
        assertTrue(result.reasons.any{it.startsWith("1m:")})
        assertTrue(result.reasons.any{it.startsWith("3m:")})
        assertTrue(result.reasons.any{it.startsWith("15m:")})
        assertTrue(result.reasons.any{it.startsWith("4h:")})
    }

    @Test fun conflictingSelectedTimeframesDoNotEmitAnySignal() {
        val oneMinute=60_000L
        val threeMinutes=180_000L
        val decision=(System.currentTimeMillis()/threeMinutes)*threeMinutes
        val rising=series(220,oneMinute,decision-220*oneMinute)
        val falling=fallingSeries(220,threeMinutes,decision-220*threeMinutes)
        val result=TradingStrategy.evaluate(mapOf(
            "1m" to rising,
            "3m" to falling
        ),decision+1L)
        assertEquals("WAIT",result.signal)
        assertTrue(result.reasons.any{it.startsWith("1m: LONG")})
        assertTrue(result.reasons.any{it.startsWith("3m: SHORT")})
        assertTrue(result.reasons.first().contains("همه تایم‌فریم‌های انتخاب‌شده"))
    }

    @Test fun timeframeMustUseItsOwnLatestClosedCandle() {
        val duration=300_000L
        val now=System.currentTimeMillis()
        val start=(now/duration)*duration-219*duration
        val rows=series(220,duration,start)
        val beforeClose=TradingStrategy.evaluate(mapOf("5m" to rows),rows.last().time+duration-1)
        assertEquals("WAIT",beforeClose.signal)
        assertTrue(beforeClose.reasons.first().contains("داده بسته‌شده بعضی تایم‌فریم‌ها تازه نیست"))
    }
}
