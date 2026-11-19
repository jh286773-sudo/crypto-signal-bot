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

/**
 * Ten-tool, multi-timeframe strategy.
 * All indicators are calculated from OHLCV candles; no external indicator values are assumed.
 * Volume Profile and SMC are transparent approximations based on available candle data.
 */
object TradingStrategy {
    private fun valid(x: Double) = x.isFinite()
    private fun emaSeries(v: List<Double>, p: Int): List<Double> {
        if (v.size < p) return emptyList()
        val out = MutableList(v.size) { Double.NaN }
        val k = 2.0 / (p + 1.0)
        var e = v.take(p).average()
        out[p - 1] = e
        for (i in p until v.size) { e = v[i] * k + e * (1.0 - k); out[i] = e }
        return out
    }
    private fun ema(v: List<Double>, p: Int) = emaSeries(v,p).lastOrNull() ?: Double.NaN

    // Wilder's smoothed RSI (not a simple gain/loss sum).
    private fun rsi(v: List<Double>, p: Int = 14): Double {
        if (v.size <= p) return Double.NaN
        val d = v.zipWithNext { a,b -> b-a }
        var g = d.take(p).map { max(it,0.0) }.average()
        var l = d.take(p).map { max(-it,0.0) }.average()
        for (x in d.drop(p)) { g = (g*(p-1)+max(x,0.0))/p; l = (l*(p-1)+max(-x,0.0))/p }
        if (l == 0.0) return if (g == 0.0) 50.0 else 100.0
        return 100.0 - 100.0/(1.0+g/l)
    }
    private fun macd(v: List<Double>): Triple<Double,Double,Double> {
        val a=emaSeries(v,12); val b=emaSeries(v,26)
        if(a.isEmpty()||b.isEmpty()) return Triple(Double.NaN,Double.NaN,Double.NaN)
        val line=(25 until v.size).map { a[it]-b[it] }
        val sig=emaSeries(line,9)
        if(sig.isEmpty()) return Triple(Double.NaN,Double.NaN,Double.NaN)
        return Triple(line.last(),sig.last(),line.last()-sig.last())
    }
    private fun bands(v: List<Double>, p:Int=20): Triple<Double,Double,Double> {
        if(v.size<p)return Triple(Double.NaN,Double.NaN,Double.NaN)
        val x=v.takeLast(p);val m=x.average();val sd=sqrt(x.map{(it-m)*(it-m)}.average())
        return Triple(m-2*sd,m,m+2*sd)
    }
    private fun trueRanges(c:List<MarketCandle>):List<Double> = c.drop(1).mapIndexed{i,x->
        val prev=c[i].close;max(x.high-x.low,max(abs(x.high-prev),abs(x.low-prev)))
    }
    private fun atr(c:List<MarketCandle>,p:Int=14):Double{
        val tr=trueRanges(c);if(tr.size<p)return Double.NaN
        var x=tr.take(p).average()
        for(v in tr.drop(p))x=(x*(p-1)+v)/p
        return x
    }
    // Wilder smoothed ADX and directional index spread.
    private fun adx(c:List<MarketCandle>,p:Int=14):Pair<Double,Double>{
        if(c.size<p+2)return Pair(Double.NaN,Double.NaN)
        val plus=mutableListOf<Double>();val minus=mutableListOf<Double>();val tr=trueRanges(c)
        for(i in 1 until c.size){
            val up=c[i].high-c[i-1].high;val dn=c[i-1].low-c[i].low
            plus.add(if(up>dn&&up>0)up else 0.0);minus.add(if(dn>up&&dn>0)dn else 0.0)
        }
        fun smooth(x:List<Double>):List<Double>{var s=x.take(p).sum();val o=mutableListOf(s);for(v in x.drop(p)){s=s-s/p+v;o.add(s)};return o}
        val ts=smooth(tr);val ps=smooth(plus);val ms=smooth(minus)
        val dx=(0 until min(ts.size,min(ps.size,ms.size))).map{i->
            val pi=if(ts[i]==0.0)0.0 else 100*ps[i]/ts[i]
            val mi=if(ts[i]==0.0)0.0 else 100*ms[i]/ts[i]
            if(pi+mi==0.0)0.0 else 100*abs(pi-mi)/(pi+mi)
        }
        if(dx.size<p)return Pair(Double.NaN,Double.NaN)
        var ax=dx.take(p).average();for(v in dx.drop(p))ax=(ax*(p-1)+v)/p
        val last=ts.last()
        val pdi=if(last==0.0)0.0 else 100*ps.last()/last
        val mdi=if(last==0.0)0.0 else 100*ms.last()/last
        return Pair(ax,pdi-mdi)
    }
    private fun vwap(c:List<MarketCandle>):Double{
        val x=c.takeLast(48);val vol=x.sumOf{max(it.volume,0.0)}
        if(vol<=0)return Double.NaN
        return x.sumOf{((it.high+it.low+it.close)/3.0)*max(it.volume,0.0)}/vol
    }
    private fun stochRsi(v:List<Double>,p:Int=14):Pair<Double,Double>{
        if(v.size< p*3)return Pair(Double.NaN,Double.NaN)
        val rsis=(p until v.size).map{rsi(v.take(it+1),p)}
        if(rsis.size<3)return Pair(Double.NaN,Double.NaN)
        val raw=rsis.mapIndexed{i,_->
            val w=rsis.subList(max(0,i-p+1),i+1);val lo=w.minOrNull()!!;val hi=w.maxOrNull()!!
            if(hi==lo)50.0 else 100*(w.last()-lo)/(hi-lo)
        }
        val k=raw.last()
        val d=raw.takeLast(3).average()
        return Pair(k,d)
    }
    // Candle-volume histogram POC and approximate 70% value area.
    private fun volumeProfile(c:List<MarketCandle>):Triple<Double,Double,Double>{
        val x=c.takeLast(100);val lo=x.minOf{it.low};val hi=x.maxOf{it.high}
        if(hi<=lo)return Triple(Double.NaN,Double.NaN,Double.NaN)
        val bins=Array(24){0.0};val step=(hi-lo)/24.0
        x.forEach{val typical=(it.high+it.low+it.close)/3.0;val idx=((typical-lo)/step).toInt().coerceIn(0,23);bins[idx]+=max(it.volume,0.0)}
        val total=bins.sum();if(total<=0)return Triple(Double.NaN,Double.NaN,Double.NaN)
        val poc=bins.indices.maxByOrNull{bins[it]}?:0
        var left=poc;var right=poc;var accumulated=bins[poc]
        while(accumulated<total*0.70&&(left>0||right<23)){
            val lv=if(left>0)bins[left-1] else -1.0;val rv=if(right<23)bins[right+1] else -1.0
            if(rv>lv){right=min(23,right+1);accumulated+=max(rv,0.0)}else{left=max(0,left-1);accumulated+=max(lv,0.0)}
        }
        return Triple(lo+(poc+0.5)*step,lo+left*step,lo+(right+1)*step)
    }
    private data class Smc(val score:Int,val notes:List<String>)
    private fun smc(c:List<MarketCandle>):Smc{
        if(c.size<40)return Smc(0,emptyList())
        val x=c.takeLast(100);val recent=x.takeLast(3);val prior=x.dropLast(3)
        val swingHigh=prior.maxOf{it.high};val swingLow=prior.minOf{it.low};val last=x.last()
        var score=0;val notes=mutableListOf<String>()
        val prevHigh=x.dropLast(10).takeLast(30).maxOf{it.high};val prevLow=x.dropLast(10).takeLast(30).minOf{it.low}
        if(last.close>swingHigh){score+=2;notes.add("BOS صعودی")}
        if(last.close<swingLow){score-=2;notes.add("BOS نزولی")}
        if(last.low<prevLow&&last.close>prevLow){score+=2;notes.add("Liquidity Sweep کف")}
        if(last.high>prevHigh&&last.close<prevHigh){score-=2;notes.add("Liquidity Sweep سقف")}
        // CHoCH: close breaks the opposite short-term structure after a prior directional swing.
        val old=x.dropLast(8).takeLast(20)
        val oldBull=old.last().close>old.first().close
        if(oldBull&&last.close<old.takeLast(6).minOf{it.low}){score-=2;notes.add("CHoCH نزولی")}
        if(!oldBull&&last.close>old.takeLast(6).maxOf{it.high}){score+=2;notes.add("CHoCH صعودی")}
        for(i in max(2,x.size-12) until x.size){
            val a=x[i-2];val b=x[i]
            if(b.low>a.high){score++;notes.add("FVG صعودی");break}
            if(b.high<a.low){score--;notes.add("FVG نزولی");break}
        }
        // Order block requires opposing candle followed by displacement beyond its range.
        val impulse=recent.last()
        val bullish=impulse.close>impulse.open&&impulse.close>swingHigh
        val bearish=impulse.close<impulse.open&&impulse.close<swingLow
        if(bullish){val ob=prior.lastOrNull{it.close<it.open};if(ob!=null){score++;notes.add("Order Block تقاضا تأییدشده")}}
        if(bearish){val ob=prior.lastOrNull{it.close>it.open};if(ob!=null){score--;notes.add("Order Block عرضه تأییدشده")}}
        return Smc(score,notes.distinct())
    }

    fun evaluate(five:List<MarketCandle>,fifteen:List<MarketCandle>,thirty:List<MarketCandle>):StrategyResult{
        require(five.size>=60&&fifteen.size>=60&&thirty.size>=60){"کندل کافی نیست"}
        val c=five.map{it.close};val h=fifteen.map{it.close};val d=thirty.map{it.close}
        val price=five.last().close
        var score=0;val reasons=mutableListOf<String>()
        // 1 RSI: trend regime with overbought/oversold rejection.
        val r=rsi(c);when{r>=55->{score+=1;reasons.add("RSI(14) مومنتوم صعودی")};r<=45->{score--;reasons.add("RSI(14) مومنتوم نزولی")};else->reasons.add("RSI خنثی")}
        // 2 MACD line/signal/histogram agreement.
        val m=macd(c);if(m.third>0&&m.first>0){score+=1;reasons.add("MACD و هیستوگرام صعودی")}else if(m.third<0&&m.first<0){score--;reasons.add("MACD و هیستوگرام نزولی")}
        // 3 EMA 9/21/50/200 trend stack, multi timeframe.
        val up5=ema(c,9)>ema(c,21)&&ema(c,21)>ema(c,50)
        val dn5=ema(c,9)<ema(c,21)&&ema(c,21)<ema(c,50)
        val up15=ema(h,9)>ema(h,21)&&ema(h,21)>ema(h,50)
        val dn15=ema(h,9)<ema(h,21)&&ema(h,21)<ema(h,50)
        val up30=ema(d,9)>ema(d,21)&&ema(d,21)>ema(d,50)&&ema(d,50)>ema(d,min(200,d.size))
        val dn30=ema(d,9)<ema(d,21)&&ema(d,21)<ema(d,50)&&ema(d,50)<ema(d,min(200,d.size))
        if(up5&&up15&&up30){score+=2;reasons.add("EMA هم‌راستای صعودی در تایم‌فریم‌های ۵، ۱۵ و ۳۰ دقیقه")}else if(dn5&&dn15&&dn30){score-=2;reasons.add("EMA هم‌راستای نزولی در تایم‌فریم‌های ۵، ۱۵ و ۳۰ دقیقه")}
        // 4 Bollinger: avoid blindly fading a band; score reclaim/rejection.
        val bb=bands(c);if(price<bb.first&&c.last()>c[c.size-2]){score++;reasons.add("بازگشت از باند پایین بولینگر")}else if(price>bb.third&&c.last()<c[c.size-2]){score--;reasons.add("رد قیمت از باند بالای بولینگر")}
        // 5 ATR is used as a volatility/risk filter and dynamic stop distance.
        val a=atr(five);val atrPct=if(a.isFinite())a/price else Double.NaN
        val volatilityOk=atrPct.isFinite()&&atrPct in 0.0003..0.04
        if(volatilityOk)reasons.add("ATR(14) فیلتر نوسان معتبر")else reasons.add("هشدار: نوسان خارج از محدوده معمول")
        // 6 ADX + DI direction.
        val ad=adx(five);if(ad.first>=22){if(ad.second>0){score++;reasons.add("ADX بالای ۲۲ و +DI غالب")}else{score--;reasons.add("ADX بالای ۲۲ و -DI غالب")}}
        // 7 VWAP location.
        val vw=vwap(five);if(vw.isFinite()){if(price>vw){score++;reasons.add("قیمت بالای VWAP")}else{score--;reasons.add("قیمت زیر VWAP")}}
        // 8 Stochastic RSI cross in extreme zone.
        val st=stochRsi(c);if(st.first<20&&st.first>st.second){score++;reasons.add("Stoch RSI صعودی از اشباع فروش")}else if(st.first>80&&st.first<st.second){score--;reasons.add("Stoch RSI نزولی از اشباع خرید")}
        // 9 Volume Profile: POC and value-area context.
        val vp=volumeProfile(five);if(vp.first.isFinite()){if(price>vp.first){score++;reasons.add("قیمت بالای Volume Profile POC")}else{score--;reasons.add("قیمت زیر Volume Profile POC")}}
        // 10 Smart Money Concepts, separate structural confirmation layer.
        val smart=smc(five);score+=smart.score
        if(!volatilityOk){score=0;reasons.add("سیگنال به‌علت فیلتر ریسک متوقف شد")}
        val signal=when{score>=5&&smart.score>=1&&up5&&up15&&up30->"LONG";score<=-5&&smart.score<=-1&&dn5&&dn15&&dn30->"SHORT";else->"WAIT"}
        val risk=max(if(a.isFinite()&&a>0)a else price*0.001,price*0.0005)
        val stop=when(signal){"LONG"->price-1.5*risk;"SHORT"->price+1.5*risk;else->price}
        val t1=when(signal){"LONG"->price+1.5*risk;"SHORT"->price-1.5*risk;else->price}
        val t2=when(signal){"LONG"->price+2.5*risk;"SHORT"->price-2.5*risk;else->price}
        return StrategyResult(signal,score,price,stop,t1,t2,reasons,smart.notes)
    }
}
