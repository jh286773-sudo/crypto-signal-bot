package com.cryptoscalp.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MarketCandle(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double = 0.0)

data class StrategyResult(
    val signal: String,
    val score: Int,
    val entry: Double,
    val stop: Double,
    val target1: Double,
    val target2: Double,
    val reasons: List<String>,
    val smc: List<String>
)

/** Six technical indicators plus a separate Smart Money Concepts confirmation layer. */
object TradingStrategy {
    private fun ema(v: List<Double>, p: Int): Double {
        if (v.size < p) return Double.NaN
        val k = 2.0 / (p + 1)
        var x = v.take(p).average()
        for (n in v.drop(p)) x = n * k + x * (1-k)
        return x
    }
    private fun rsi(v: List<Double>, p: Int = 14): Double {
        if (v.size <= p) return Double.NaN
        val d = v.takeLast(p+1).zipWithNext { a,b -> b-a }
        var g = 0.0; var l = 0.0
        d.forEach { if (it > 0) g += it else l -= it }
        if (l == 0.0) return 100.0
        return 100.0 - 100.0/(1.0 + g/l)
    }
    private fun macd(v: List<Double>): Pair<Double,Double> {
        if (v.size < 35) return Pair(Double.NaN, Double.NaN)
        val line = v.indices.map { i ->
            val sub = v.take(i+1)
            if (sub.size < 26) 0.0 else ema(sub,12)-ema(sub,26)
        }
        return Pair(line.last(), ema(line.drop(25),9))
    }
    private fun bands(v: List<Double>): Triple<Double,Double,Double> {
        val a=v.takeLast(20); val m=a.average()
        val sd=sqrt(a.map{(it-m)*(it-m)}.average())
        return Triple(m-2*sd,m,m+2*sd)
    }
    private fun atr(c: List<MarketCandle>, p:Int=14):Double {
        if(c.size<p+1) return Double.NaN
        return c.takeLast(p).mapIndexed { i,x ->
            val prev=c[c.size-p+i-1].close
            max(x.high-x.low,max(abs(x.high-prev),abs(x.low-prev)))
        }.average()
    }
    private fun adx(c:List<MarketCandle>,p:Int=14):Pair<Double,Double> {
        if(c.size<p+2) return Pair(Double.NaN,Double.NaN)
        val rows=c.takeLast(p+1).zipWithNext()
        var plus=0.0; var minus=0.0; var tr=0.0
        rows.forEach { (a,b) ->
            val up=b.high-a.high; val dn=a.low-b.low
            if(up>dn && up>0) plus+=up
            if(dn>up && dn>0) minus+=dn
            tr+=max(b.high-b.low,max(abs(b.high-a.close),abs(b.low-a.close)))
        }
        if(tr<=0) return Pair(0.0,0.0)
        val pdi=100*plus/tr; val mdi=100*minus/tr
        val dx=if(pdi+mdi==0.0) 0.0 else 100*abs(pdi-mdi)/(pdi+mdi)
        return Pair(dx,pdi-mdi)
    }
    private fun smc(c:List<MarketCandle>):Pair<Int,List<String>> {
        val x=c.takeLast(40); if(x.size<20) return Pair(0, emptyList())
        val recent=x.takeLast(5); val prior=x.dropLast(5)
        val swingHigh=prior.maxOf{it.high}; val swingLow=prior.minOf{it.low}
        var score=0; val notes=mutableListOf<String>()
        val last=recent.last()
        if(last.close>swingHigh){score+=2;notes.add("BOS صعودی")}
        if(last.close<swingLow){score-=2;notes.add("BOS نزولی")}
        // Liquidity sweep: wick beyond prior liquidity, close back inside.
        if(recent.any{it.high>swingHigh && it.close<swingHigh}){score-=2;notes.add("Liquidity Sweep بالای سقف")}
        if(recent.any{it.low<swingLow && it.close>swingLow}){score+=2;notes.add("Liquidity Sweep زیر کف")}
        // Fair Value Gap on three-candle structure.
        for(i in 2 until x.size){
            val a=x[i-2]; val b=x[i]
            if(b.low>a.high){score++;notes.add("FVG صعودی");break}
            if(b.high<a.low){score--;notes.add("FVG نزولی");break}
        }
        // Last opposing candle before displacement as a simple OB proxy.
        val impulse=recent.last()
        val opposing=prior.lastOrNull{ if(impulse.close>impulse.open) it.close<it.open else it.close>it.open }
        if(opposing!=null){
            if(impulse.close>impulse.open){score++;notes.add("Order Block تقاضا")}
            else {score--;notes.add("Order Block عرضه")}
        }
        return Pair(score,notes.distinct())
    }

    fun evaluate(one:List<MarketCandle>, five:List<MarketCandle>, fifteen:List<MarketCandle>, hour:List<MarketCandle>):StrategyResult {
        require(one.size>=60 && five.size>=60 && fifteen.size>=60 && hour.size>=60) { "کندل کافی نیست" }
        val c=five.map{it.close}; val h=fifteen.map{it.close}; val d=hour.map{it.close}
        val price=one.last().close
        var score=0; val reasons=mutableListOf<String>()
        // 1 RSI
        val r=rsi(c); if(r>52){score++;reasons.add("RSI صعودی")} else if(r<48){score--;reasons.add("RSI نزولی")}
        // 2 MACD
        val m=macd(c); if(m.first>m.second){score++;reasons.add("MACD مثبت")} else {score--;reasons.add("MACD منفی")}
        // 3 EMA trend alignment
        val e1=ema(d,9)>ema(d,21)&&ema(d,21)>ema(d,50)
        val e2=ema(h,9)>ema(h,21)&&ema(h,21)>ema(h,50)
        if(e1&&e2){score+=2;reasons.add("EMA هم‌راستا در ۱ساعت و ۱۵دقیقه")}
        else if(ema(d,9)<ema(d,21)&&ema(d,21)<ema(d,50)&&ema(h,9)<ema(h,21)&&ema(h,21)<ema(h,50)){score-=2;reasons.add("EMA نزولی در دو تایم‌فریم")}
        // 4 Bollinger Bands
        val bb=bands(c); if(price<bb.first){score++;reasons.add("قیمت زیر باند پایینی بولینگر")} else if(price>bb.third){score--;reasons.add("قیمت بالای باند بالایی بولینگر")}
        // 5 ATR volatility filter / risk sizing
        val a=atr(five); if(a.isFinite() && a>0){reasons.add("ATR(14) نوسان‌پذیری را تأیید کرد")}
        // 6 ADX trend strength
        val ad=adx(five); if(ad.first>=20){if(ad.second>0){score++;reasons.add("ADX روند صعودی قوی")}else{score--;reasons.add("ADX روند نزولی قوی")}}
        // Separate SMC layer
        val smart=smc(five); score+=smart.first
        val signal=when { score>=4 && smart.first>0 -> "LONG"; score<=-4 && smart.first<0 -> "SHORT"; else -> "WAIT" }
        val risk=max(if(a.isFinite()) a else price*0.001,price*0.0005)
        val stop=if(signal=="LONG")price-1.5*risk else price+1.5*risk
        val t1=if(signal=="LONG")price+1.5*risk else price-1.5*risk
        val t2=if(signal=="LONG")price+2.5*risk else price-2.5*risk
        return StrategyResult(signal,score,price,stop,t1,t2,reasons,smart.second)
    }
}
