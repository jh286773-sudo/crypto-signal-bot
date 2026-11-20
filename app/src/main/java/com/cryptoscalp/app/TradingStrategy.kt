package com.cryptoscalp.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

data class MarketCandle(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double = 0.0, val time: Long = 0L, val takerBuyVolume: Double? = null)
data class StrategyResult(val signal: String, val score: Int, val entry: Double, val reasons: List<String>, val smc: List<String>)

/** AURUM X 0.3.0 read-only engine: seven-indicator suite and 5m/15m/30m consensus. */
object TradingStrategy {
    private const val MIN_CANDLES = 200
    private val tfWeights = mapOf("1m" to .15, "3m" to .20, "5m" to .25, "15m" to .35, "30m" to .40, "1h" to .45, "4h" to .50)
    private val familyWeights = mapOf("trend" to 1.5, "momentum" to 1.3, "strength" to 1.1, "volume" to 1.0, "volatility" to .8)
    private data class ADX(val value: Double, val plus: Double, val minus: Double)
    private data class Ichi(val tenkan: Double, val kijun: Double, val spanA: Double, val spanB: Double)
    private data class Structure(val event: String, val sweep: String, val marketTrend: String = "range", val equalHighs: Boolean = false, val equalLows: Boolean = false, val orderBlockType: String = "none", val orderBlockLow: Double? = null, val orderBlockHigh: Double? = null, val previousSwingHigh: Double? = null, val previousSwingLow: Double? = null, val retestLong: Boolean = false, val retestShort: Boolean = false, val falseBreakout: Boolean = false, val retestLevel: Double? = null, val entryDistanceAtr: Double? = null, val equalHighLevel: Double? = null, val equalLowLevel: Double? = null)
    private data class Report(val valid: Boolean, val reason: String = "", val price: Double = 0.0, val score: Double = 0.0, val bias: String = "neutral", val atr: Double? = null, val ema21: Double? = null, val families: Map<String,Double> = emptyMap(), val indicatorVotes: Map<String,Int> = emptyMap(), val notes: List<String> = emptyList(), val smc: List<String> = emptyList(), val highVolatility: Boolean = false, val atrPct: Double? = null, val volatilityRatio: Double? = null, val marketRegime: String = "unknown", val adxValue: Double? = null, val structure: Structure? = null)

    private fun ema(v: List<Double>, n: Int): Double? {
        if (v.size < n) return null
        val a = 2.0 / (n + 1.0)
        var x = v.take(n).average()
        for (z in v.drop(n)) x = a*z + (1-a)*x
        return x
    }
    private fun sma(v: List<Double>, n: Int) = if (v.size < n) null else v.takeLast(n).average()
    private fun rsi(v: List<Double>, n: Int = 14): Double? {
        if (v.size < n+1) return null
        val d = (1 until v.size).map { v[it]-v[it-1] }
        var g = d.take(n).sumOf { max(it,0.0) }/n
        var l = d.take(n).sumOf { max(-it,0.0) }/n
        for (x in d.drop(n)) { g=(g*(n-1)+max(x,0.0))/n; l=(l*(n-1)+max(-x,0.0))/n }
        return if (l==0.0) 100.0 else 100.0-100.0/(1.0+g/l)
    }
    private fun emaSeries(v:List<Double>,n:Int):List<Double?> {
        val out=MutableList<Double?>(v.size){null}
        if(v.size<n)return out
        val alpha=2.0/(n+1.0)
        var value=v.take(n).average()
        out[n-1]=value
        for(i in n until v.size){value=alpha*v[i]+(1.0-alpha)*value;out[i]=value}
        return out
    }
    private fun macd(v: List<Double>): Triple<Double?,Double?,Double?> {
        if (v.size<35) return Triple(null,null,null)
        val fast=emaSeries(v,12);val slow=emaSeries(v,26)
        val vals=(25 until v.size).map{(fast[it]?:0.0)-(slow[it]?:0.0)}
        val line=vals.last()
        val signal=ema(vals,9)
        return Triple(line,signal,signal?.let{line-it})
    }
    private fun atr(c: List<MarketCandle>, n:Int=14):Double? {
        if(c.size<n+1)return null
        val tr=(1 until c.size).map { i -> val p=c[i-1].close; max(c[i].high-c[i].low,max(abs(c[i].high-p),abs(c[i].low-p))) }
        var x=tr.take(n).average()
        for(z in tr.drop(n))x=(x*(n-1)+z)/n
        return x
    }
    private fun bb(v:List<Double>,n:Int=20):Triple<Double,Double,Double>? {
        if(v.size<n)return null
        val w=v.takeLast(n);val m=w.average();val sd=sqrt(w.sumOf{(it-m)*(it-m)}/n)
        return Triple(m-2*sd,m,m+2*sd)
    }
    private fun stochRsi(v:List<Double>,n:Int=14):Double? {
        if(v.size<2*n+1)return null
        val deltas=(1 until v.size).map{v[it]-v[it-1]}
        var avgGain=deltas.take(n).sumOf{max(it,0.0)}/n
        var avgLoss=deltas.take(n).sumOf{max(-it,0.0)}/n
        val rs=mutableListOf(if(avgLoss==0.0)100.0 else 100.0-100.0/(1.0+avgGain/avgLoss))
        for(delta in deltas.drop(n)){
            avgGain=(avgGain*(n-1)+max(delta,0.0))/n
            avgLoss=(avgLoss*(n-1)+max(-delta,0.0))/n
            rs.add(if(avgLoss==0.0)100.0 else 100.0-100.0/(1.0+avgGain/avgLoss))
        }
        if(rs.size<n)return null
        val w=rs.takeLast(n);val lo=w.minOrNull()!!;val hi=w.maxOrNull()!!
        return if(hi==lo)0.0 else 100*(rs.last()-lo)/(hi-lo)
    }
    private fun adx(c:List<MarketCandle>,n:Int=14):ADX? {
        if(c.size<2*n+1)return null
        val p=mutableListOf<Double>();val m=mutableListOf<Double>();val tr=mutableListOf<Double>()
        for(i in 1 until c.size){
            val up=c[i].high-c[i-1].high;val dn=c[i-1].low-c[i].low
            p.add(if(up>dn&&up>0)up else 0.0);m.add(if(dn>up&&dn>0)dn else 0.0)
            val pc=c[i-1].close;tr.add(max(c[i].high-c[i].low,max(abs(c[i].high-pc),abs(c[i].low-pc))))
        }
        var ps=p.take(n).sum();var ms=m.take(n).sum();var ts=tr.take(n).sum();val dx=mutableListOf<Double>()
        for(i in n until tr.size){
            ps=ps-ps/n+p[i];ms=ms-ms/n+m[i];ts=ts-ts/n+tr[i]
            if(ts!=0.0){val pi=100*ps/ts;val mi=100*ms/ts;dx.add(if(pi+mi==0.0)0.0 else 100*abs(pi-mi)/(pi+mi))}
        }
        if(dx.size<n||ts==0.0)return null
        var ax=dx.take(n).average();for(x in dx.drop(n))ax=(ax*(n-1)+x)/n
        return ADX(ax,100*ps/ts,100*ms/ts)
    }
    private fun ichimoku(c:List<MarketCandle>):Ichi? {
        if(c.size<52)return null
        fun mid(n:Int):Double{val w=c.takeLast(n);return (w.maxOf{it.high}+w.minOf{it.low})/2}
        val t=mid(9);val k=mid(26);return Ichi(t,k,(t+k)/2,mid(52))
    }
    private fun vwap(c:List<MarketCandle>,n:Int=20):Double? {
        val w=c.takeLast(n);val vol=w.sumOf{max(it.volume,0.0)}
        if(vol<=0)return null
        return w.sumOf{((it.high+it.low+it.close)/3)*max(it.volume,0.0)}/vol
    }
    private fun obvTrend(c:List<MarketCandle>,lookback:Int=8):Int {
        if(c.size<lookback+1)return 0
        var delta=0.0
        for(i in c.size-lookback until c.size) {
            val volume=max(c[i].volume,0.0)
            delta+=when { c[i].close>c[i-1].close->volume; c[i].close<c[i-1].close->-volume; else->0.0 }
        }
        return if(delta>0)1 else if(delta<0)-1 else 0
    }
    private fun volumeImpulse(c:List<MarketCandle>,currentAtr:Double?):Int {
        if(c.size<26||currentAtr==null||currentAtr<=0.0)return 0
        val recent=c.takeLast(5).map{max(it.volume,0.0)}.average()
        val baseline=c.dropLast(5).takeLast(20).map{max(it.volume,0.0)}.average()
        if(baseline<=0.0||recent/baseline<1.3)return 0
        val last=c.last();val span=last.high-last.low
        if(span<=0.0||abs(last.close-last.open)<currentAtr*.35)return 0
        val location=(last.close-last.low)/span
        return if(last.close>last.open&&location>=.70)1 else if(last.close<last.open&&location<=.30)-1 else 0
    }
    private fun mfi(c:List<MarketCandle>,n:Int=14):Double? {
        if(c.size<n+1)return null
        fun tp(x:MarketCandle)=(x.high+x.low+x.close)/3
        var pos=0.0;var neg=0.0
        for(i in c.size-n until c.size){val flow=tp(c[i])*max(c[i].volume,0.0);if(tp(c[i])>tp(c[i-1]))pos+=flow else if(tp(c[i])<tp(c[i-1]))neg+=flow}
        return if(neg==0.0)100.0 else 100-100/(1+pos/neg)
    }
    private fun confirmedPivots(values:List<Double>,left:Int=2,right:Int=2):Pair<List<Int>,List<Int>> {
        val highs=mutableListOf<Int>();val lows=mutableListOf<Int>()
        for(i in left until values.size-right){
            val w=values.subList(i-left,i+right+1)
            if(values[i]==w.maxOrNull()&&w.count{it==values[i]}==1)highs.add(i)
            if(values[i]==w.minOrNull()&&w.count{it==values[i]}==1)lows.add(i)
        }
        return highs to lows
    }
    private fun divergence(c:List<MarketCandle>,lookback:Int=48):String {
        if(c.size<lookback+20)return "none"
        val values=c.map{it.close};val start=values.size-lookback-2
        val (hi,lo)=confirmedPivots(values.takeLast(lookback+2),2,2)
        val lows=lo.map{it+start};val highs=hi.map{it+start}
        if(lows.size>=2){
            val p1=lows[lows.size-2];val p2=lows.last()
            val r1=rsi(values.take(p1+1));val r2=rsi(values.take(p2+1))
            if(r1!=null&&r2!=null&&values[p2]<values[p1]&&r2>r1+3)return "bullish"
        }
        if(highs.size>=2){
            val p1=highs[highs.size-2];val p2=highs.last()
            val r1=rsi(values.take(p1+1));val r2=rsi(values.take(p2+1))
            if(r1!=null&&r2!=null&&values[p2]>values[p1]&&r2<r1-3)return "bearish"
        }
        return "none"
    }
    private fun structure(c:List<MarketCandle>,n:Int=60):Structure {
        if(c.size<max(n,30))return Structure("none","none")
        val w=c.takeLast(n)
        val highs=w.map{it.high};val lows=w.map{it.low}
        val (hi,lo)=confirmedPivots(highs,2,2)
        // Ignore pivots that are not yet confirmed at the decision candle.
        val swingHighs=hi.filter{it<w.size-4}.map{it to highs[it]}
        val swingLows=lo.filter{it<w.size-4}.map{it to lows[it]}
        val last=w.last()
        val structureAtr=atr(w)
        val atrRef=(structureAtr?.takeIf{it>0.0})?:max(last.close*.001,1e-12)
        val tolerance=max(atrRef*.20,last.close*.0005)
        val breakBuffer=atrRef*.10
        val span=max(last.high-last.low,1e-12)
        val closePosition=(last.close-last.low)/span
        val displacement=abs(last.close-last.open)>=atrRef*.35
        val sh:Double
        val sl:Double
        val recent:List<MarketCandle>
        val highPivots:List<Pair<Int,Double>>
        val lowPivots:List<Pair<Int,Double>>
        if(swingHighs.isEmpty()||swingLows.isEmpty()){
            val prior=c.takeLast(21).dropLast(1)
            if(prior.size<10)return Structure("none","none")
            val reference=if(prior.size>=16)prior.dropLast(6) else prior
            sh=reference.maxOf{it.high};sl=reference.minOf{it.low}
            recent=prior.takeLast(6)
            highPivots=listOf(0 to sh);lowPivots=listOf(0 to sl)
        }else{
            sh=swingHighs.last().second;sl=swingLows.last().second
            recent=w.dropLast(1).takeLast(6)
            highPivots=swingHighs;lowPivots=swingLows
        }
        val up=swingHighs.size>=2&&swingLows.size>=2&&swingHighs.last().second>swingHighs[swingHighs.size-2].second&&swingLows.last().second>swingLows[swingLows.size-2].second
        val down=swingHighs.size>=2&&swingLows.size>=2&&swingHighs.last().second<swingHighs[swingHighs.size-2].second&&swingLows.last().second<swingLows[swingLows.size-2].second
        val event=when{
            last.close>sh+breakBuffer&&displacement&&closePosition>=.70->if(down)"CHoCH_up" else "BOS_up"
            last.close<sl-breakBuffer&&displacement&&closePosition<=.30->if(up)"CHoCH_down" else "BOS_down"
            else->"none"
        }
        val sweep=when{
            last.low<sl-tolerance*.10&&last.close>sl&&closePosition>=.55->"sell_side"
            last.high>sh+tolerance*.10&&last.close<sh&&closePosition<=.45->"buy_side"
            else->"none"
        }
        val equalHighs=highPivots.takeLast(6).count{abs(it.second-sh)<=tolerance}>=2
        val equalLows=lowPivots.takeLast(6).count{abs(it.second-sl)<=tolerance}>=2
        val priorUp=recent.any{it.close>sh+breakBuffer}
        val priorDown=recent.any{it.close<sl-breakBuffer}
        val bullCandle=last.close>last.open&&closePosition>=.60
        val bearCandle=last.close<last.open&&closePosition<=.40
        // A liquidity sweep alone is not an entry trigger. Require a prior
        // close-confirmed structural break followed by a directional retest.
        val retestLong=priorUp&&last.low<=sh+tolerance&&last.close>sh&&bullCandle
        val retestShort=priorDown&&last.high>=sl-tolerance&&last.close<sl&&bearCandle
        val falseBreakout=(priorUp&&last.close<sh-tolerance)||(priorDown&&last.close>sl+tolerance)
        var obType="none";var obLow:Double?=null;var obHigh:Double?=null
        for(j in w.size-2 downTo max(0,w.size-20)){
            val x=w[j];val next=w[j+1];val nextRange=max(next.high-next.low,1e-12)
            val impulseBody=abs(next.close-next.open)
            val bullishValid=x.close<x.open&&next.close>x.high&&next.close>=next.high-nextRange*.25&&
                impulseBody>=atrRef*.50&&w.drop(j+2).none{it.close<x.low}
            val bearishValid=x.close>x.open&&next.close<x.low&&next.close<=next.low+nextRange*.25&&
                impulseBody>=atrRef*.50&&w.drop(j+2).none{it.close>x.high}
            if(bullishValid){obType="bullish";obLow=x.low;obHigh=x.high;break}
            if(bearishValid){obType="bearish";obLow=x.low;obHigh=x.high;break}
        }
        val retestLevel=when{retestLong->if(priorUp)sh else sl;retestShort->if(priorDown)sl else sh;else->null}
        val distance=retestLevel?.let{level->max(0.0,if(retestLong)(last.close-level)/atrRef else (level-last.close)/atrRef)}
        return Structure(event=event,sweep=sweep,marketTrend=if(up)"bullish" else if(down)"bearish" else "range",
            equalHighs=equalHighs,equalLows=equalLows,orderBlockType=obType,orderBlockLow=obLow,orderBlockHigh=obHigh,
            previousSwingHigh=sh,previousSwingLow=sl,retestLong=retestLong,retestShort=retestShort,
            falseBreakout=falseBreakout,retestLevel=retestLevel,entryDistanceAtr=distance,
            equalHighLevel=if(equalHighs)sh else null,equalLowLevel=if(equalLows)sl else null)
    }
    private data class Volatility(val atrPct:Double,val baselinePct:Double,val ratio:Double,val blocked:Boolean)
    private fun volatility(c:List<MarketCandle>,timeframe:String,currentAtr:Double?):Volatility? {
        if(currentAtr==null||currentAtr<=0.0||c.isEmpty()||c.last().close<=0.0)return null
        val pct=100.0*currentAtr/c.last().close
        val start=max(15,c.size-30)
        val baseline=(start until c.size-1).mapNotNull{end->
            val a=atr(c.take(end+1));val close=c[end].close
            if(a!=null&&close>0.0)100.0*a/close else null
        }.sorted()
        if(baseline.isEmpty())return Volatility(pct,0.0,0.0,false)
        val mid=baseline.size/2
        val median=if(baseline.size%2==0)(baseline[mid-1]+baseline[mid])/2.0 else baseline[mid]
        val ratio=if(median>0.0)pct/median else 0.0
        val floor=when(timeframe){"5m"->0.8;"15m"->1.2;"30m"->1.8;else->0.8}
        return Volatility(pct,median,ratio,pct>=floor&&ratio>=2.5)
    }

    private fun analyze(c:List<MarketCandle>,timeframe:String):Report {
        if(c.size<MIN_CANDLES)return Report(false,"حداقل ۲۰۰ کندل برای EMA200 لازم است")
        // All history feeds recursive indicators, so validate every candle,
        // not only the visible tail; an old NaN can otherwise poison EMA/ATR.
        if(c.any{
            !it.open.isFinite()||!it.high.isFinite()||!it.low.isFinite()||
            !it.close.isFinite()||!it.volume.isFinite()||
            it.open<=0||it.high<=0||it.low<=0||it.close<=0||it.volume<0||
            it.high<max(it.open,it.close)||it.low>min(it.open,it.close)||it.high<it.low||
            (it.takerBuyVolume!=null&&(!it.takerBuyVolume.isFinite()||it.takerBuyVolume<0.0||it.takerBuyVolume>it.volume*1.001))
        }) return Report(false,"داده کندل نامعتبر است")
        val v=c.map{it.close};val price=v.last()
        val e20=ema(v,20)!!;val e50=ema(v,50)!!;val e200=ema(v,200)!!
        val r=rsi(v);val mc=macd(v);val bands=bb(v)
        val dx=adx(c);val a=atr(c);val vol=volatility(c,timeframe,a);val vw=vwap(c);val ov=obvTrend(c)
        val sm=structure(c)
        val regime=when{dx==null->"unknown";dx.value>=25->"trending";dx.value<18->"ranging";else->"transition"}
        val groups=linkedMapOf<String,MutableList<Pair<Double,Double>>>();val notes=mutableListOf<String>();val indicatorVotes=linkedMapOf<String,Int>()
        fun vote(group:String,name:String,value:Int,weight:Double=1.0){
            groups.getOrPut(group){mutableListOf()}.add(value.toDouble() to weight)
            indicatorVotes[name]=value
            if(value!=0)notes.add(name+":"+(if(value>0)"+" else "")+value)
        }
        vote("trend","EMA",if(e20>e50&&e50>e200)1 else if(e20<e50&&e50<e200)-1 else 0,1.2)
        vote("momentum","RSI",if(r!=null&&r>52&&r<70)1 else if(r!=null&&r>30&&r<48)-1 else 0)
        vote("momentum","MACD",mc.third?.let{if(it>0)1 else if(it<0)-1 else 0}?:0,1.2)
        // ADX measures strength; DI+/DI- provide direction without an ADX magnitude gate.
        vote("strength","ADX_DMI",if(dx!=null&&dx.plus>dx.minus)1 else if(dx!=null&&dx.minus>dx.plus)-1 else 0,1.2)
        vote("volume","VWAP",if(vw!=null&&price>vw)1 else if(vw!=null&&price<vw)-1 else 0)
        vote("volume","OBV_Trend",ov)
        vote("volatility","Bollinger",if(bands!=null&&price>bands.second)1 else if(bands!=null&&price<bands.second)-1 else 0)
        val obTolerance=(a?:price*.001)*.25
        val inBullOb=sm.orderBlockType=="bullish"&&sm.orderBlockLow!=null&&sm.orderBlockHigh!=null&&price>=sm.orderBlockLow!!-obTolerance&&price<=sm.orderBlockHigh!!+obTolerance
        val inBearOb=sm.orderBlockType=="bearish"&&sm.orderBlockLow!=null&&sm.orderBlockHigh!=null&&price>=sm.orderBlockLow!!-obTolerance&&price<=sm.orderBlockHigh!!+obTolerance
        val smcBull=sm.event=="BOS_up"||sm.event=="CHoCH_up"||sm.sweep=="sell_side"||inBullOb
        val smcBear=sm.event=="BOS_down"||sm.event=="CHoCH_down"||sm.sweep=="buy_side"||inBearOb
        // SMC remains an independent entry-structure safeguard, not an extra indicator vote.
        val families=groups.mapValues{(_,items)->val den=items.sumOf{it.second};if(den==0.0)0.0 else items.sumOf{it.first*it.second}/den}
        val activeWeights=familyWeights
        val den=families.keys.sumOf{activeWeights[it]?:0.0}
        val score=if(den==0.0)0.0 else 100*families.entries.sumOf{it.value*(activeWeights[it.key]?:0.0)}/den
        val up=families.values.count{it>0.0};val down=families.values.count{it<0.0}
        val bias=if(up>down)"bullish" else if(down>up)"bearish" else "neutral"
        val smc=mutableListOf<String>()
        if(sm.event=="BOS_up")smc.add("BOS صعودی")
        if(sm.event=="BOS_down")smc.add("BOS نزولی")
        if(sm.event=="CHoCH_up")smc.add("تغییر کاراکتر صعودی CHoCH")
        if(sm.event=="CHoCH_down")smc.add("تغییر کاراکتر نزولی CHoCH")
        if(sm.sweep=="sell_side")smc.add("جمع‌آوری نقدینگی کف")
        if(sm.sweep=="buy_side")smc.add("جمع‌آوری نقدینگی سقف")
        if(sm.equalHighs)smc.add("استخر نقدینگی سقف‌های برابر")
        if(sm.equalLows)smc.add("استخر نقدینگی کف‌های برابر")
        if(sm.falseBreakout)smc.add("شکست جعلی/جمع‌آوری نقدینگی؛ ورود ممنوع")
        if(sm.retestLong)smc.add("بازگشت و تثبیت صعودی روی سطح شکست")
        if(sm.retestShort)smc.add("بازگشت و تثبیت نزولی زیر سطح شکست")
        if(sm.orderBlockType!="none")smc.add("Order Block "+sm.orderBlockType)
        return Report(true,price=price,score=score,bias=bias,atr=a,ema21=e20,families=families,indicatorVotes=indicatorVotes,notes=notes,smc=smc,highVolatility=vol?.blocked?:false,atrPct=vol?.atrPct,volatilityRatio=vol?.ratio,marketRegime=regime,adxValue=dx?.value,structure=sm)
    }

    internal fun entryNotExtended(price:Double,ema21:Double?,atr:Double?,maxDistanceAtr:Double=.80):Boolean {
        if(ema21==null||atr==null||!price.isFinite()||!ema21.isFinite()||!atr.isFinite()||atr<=0.0)return false
        return abs(price-ema21)/atr<=maxDistanceAtr
    }

    internal fun noMajorIndicatorConflict(votes:Map<String,Int>,direction:Int):Boolean {
        val core=setOf("EMA","RSI","MACD","ADX_DMI","VWAP","OBV_Trend","Bollinger")
        val opposingCore=core.count{(votes[it]?:0)*direction<0}
        val opposingMinor=votes.count{(it.key !in core)&&it.value*direction<0}
        return opposingCore==0&&opposingMinor<=1
    }

    fun evaluate(five:List<MarketCandle>,fifteen:List<MarketCandle>,thirty:List<MarketCandle>,asOfMs:Long=System.currentTimeMillis()):StrategyResult =
        evaluate(mapOf("5m" to five, "15m" to fifteen, "30m" to thirty), asOfMs)

    /** Every selected timeframe is independently analyzed; any dissent or missing data returns WAIT. */
    fun evaluate(timeframes:Map<String,List<MarketCandle>>,asOfMs:Long=System.currentTimeMillis()):StrategyResult {
        val supported = listOf("1m","3m","5m","15m","30m","1h","4h")
        val selected = supported.filter { timeframes.containsKey(it) }
        if(selected.isEmpty()) return StrategyResult("WAIT",0,0.0,listOf("هیچ تایم‌فریمی انتخاب نشده؛ سیگنالی صادر نمی‌شود"),emptyList())
        val durations = mapOf("1m" to 60_000L,"3m" to 180_000L,"5m" to 300_000L,
            "15m" to 900_000L,"30m" to 1_800_000L,"1h" to 3_600_000L,"4h" to 14_400_000L)
        fun validSeries(rows:List<MarketCandle>,duration:Long):Boolean {
            if(rows.size<MIN_CANDLES||rows.any{it.time<=0L||it.time%duration!=0L})return false
            for(i in 1 until rows.size)if(rows[i].time<=rows[i-1].time||rows[i].time-rows[i-1].time!=duration)return false
            return true
        }
        val missing = selected.filter { timeframes[it].isNullOrEmpty() }
        if(missing.isNotEmpty()) return StrategyResult("WAIT",0,0.0,
            listOf("داده تایم‌فریم انتخاب‌شده موجود نیست: "+missing.joinToString("، ")),emptyList())
        val raw = selected.associateWith { timeframes[it].orEmpty() }
        if(selected.any { !validSeries(raw.getValue(it),durations.getValue(it)) }) {
            return StrategyResult("WAIT",0,raw[selected.first()]?.lastOrNull()?.close?:0.0,
                listOf("داده یا زمان‌بندی حداقل یکی از تایم‌فریم‌های انتخاب‌شده ناقص/ناپیوسته است؛ سیگنالی صادر نمی‌شود"),emptyList())
        }
        val latestCommonClose = selected.minOf { tf -> raw.getValue(tf).last().time + durations.getValue(tf) }
        val freshnessWindow = selected.maxOf { durations.getValue(it) }
        if(latestCommonClose>asOfMs || asOfMs-latestCommonClose>=freshnessWindow) {
            return StrategyResult("WAIT",0,raw[selected.first()]!!.last().close,
                listOf("کندل بسته‌شده مشترک تازه برای همه تایم‌فریم‌ها در دسترس نیست؛ سیگنالی صادر نمی‌شود"),emptyList())
        }
        val aligned = linkedMapOf<String,List<MarketCandle>>()
        for(tf in selected) {
            val duration=durations.getValue(tf)
            val expected=(latestCommonClose/duration)*duration-duration
            val rows=raw.getValue(tf).filter{it.time+duration<=latestCommonClose}
            if(rows.size<MIN_CANDLES||rows.lastOrNull()?.time!=expected) {
                return StrategyResult("WAIT",0,raw.getValue(tf).last().close,
                    listOf("کندل بسته‌شده تایم‌فریم $tf با مرز تصمیم مشترک هماهنگ نیست؛ سیگنالی صادر نمی‌شود"),emptyList())
            }
            aligned[tf]=rows
        }
        val reports=linkedMapOf<String,Report>()
        for(tf in selected) reports[tf]=analyze(aligned.getValue(tf),tf)
        val invalid=reports.filterValues{!it.valid}.keys
        val price=reports[selected.first()]?.price?:0.0
        if(invalid.isNotEmpty())return StrategyResult("WAIT",0,price,
            listOf("اطلاعات کافی/معتبر نیست: "+invalid.joinToString("، ")+"؛ سیگنالی صادر نمی‌شود"),emptyList())
        val weightTotal=selected.sumOf { tfWeights[it] ?: 0.0 }
        val combined=reports.entries.sumOf{it.value.score*(tfWeights[it.key]?:0.0)/weightTotal}
        val volatilityBlocked=reports.filterValues{it.highVolatility}.keys
        if(volatilityBlocked.isNotEmpty())return StrategyResult("WAIT",combined.roundToInt(),price,
            listOf("فیلتر نوسان غیرعادی در "+volatilityBlocked.joinToString("، ")+" فعال است؛ سیگنالی صادر نمی‌شود"),emptyList())
        val entryTf=selected.first()
        val higher=selected.drop(1)
        if(higher.size>=2&&higher.all{reports.getValue(it).marketRegime=="ranging"}) {
            return StrategyResult("WAIT",combined.roundToInt(),price,
                listOf("تمام تایم‌فریم‌های بالاترِ انتخاب‌شده بازار رنج را نشان می‌دهند؛ سیگنالی صادر نمی‌شود"),emptyList())
        }
        val allBullish=reports.values.all{it.bias=="bullish"&&noMajorIndicatorConflict(it.indicatorVotes,1)}
        val allBearish=reports.values.all{it.bias=="bearish"&&noMajorIndicatorConflict(it.indicatorVotes,-1)}
        val direction=when { allBullish->"LONG"; allBearish->"SHORT"; else->"WAIT" }
        if(direction=="WAIT") return StrategyResult("WAIT",combined.roundToInt(),price,
            listOf("همه تایم‌فریم‌های انتخاب‌شده باید بدون استثنا هم‌جهت باشند؛ اختلاف یا حالت خنثی، سیگنال را متوقف می‌کند"),reports.values.flatMap{it.smc}.distinct())
        val trigger=reports.getValue(entryTf)
        val sm=trigger.structure
        val isLong=direction=="LONG"
        val retest=if(isLong) sm?.retestLong==true else sm?.retestShort==true
        if(sm==null||sm.falseBreakout||!retest) return StrategyResult("WAIT",combined.roundToInt(),price,
            listOf(if(sm?.falseBreakout==true)"شکست جعلی در تایم‌فریم ورود شناسایی شد؛ سیگنالی صادر نمی‌شود" else "تأیید Retest در کوتاه‌ترین تایم‌فریم انتخاب‌شده کامل نیست؛ سیگنالی صادر نمی‌شود"),
            reports.values.flatMap{it.smc}.distinct())
        val a=trigger.atr?:0.0
        val distance=sm.entryDistanceAtr
        if(a<=0.0||distance==null||distance>.50||!entryNotExtended(price,trigger.ema21,a)) {
            return StrategyResult("WAIT",combined.roundToInt(),price,
                listOf("ATR/فاصله Retest/فاصله EMA21 معتبر نیست یا ورود بیش‌ازحد کشیده شده؛ سیگنالی صادر نمی‌شود"),
                reports.values.flatMap{it.smc}.distinct())
        }
        val reasons=mutableListOf("تحلیل مستقل تمام تایم‌فریم‌های انتخاب‌شده: "+selected.joinToString("، "))
        reasons.add("اجماع کامل "+selected.size+" تایم‌فریم و تأیید ساختار ورود در $entryTf")
        reasons.add(if(isLong)"همه تایم‌فریم‌ها LONG؛ سیگنال لانگ" else "همه تایم‌فریم‌ها SHORT؛ سیگنال شورت")
        return StrategyResult(direction,combined.roundToInt(),price,reasons,
            reports.flatMap{(tf,rep)->rep.smc.map{tf+": "+it}}.distinct())
    }
}