package com.cryptoscalp.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

data class MarketCandle(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double = 0.0, val time: Long = 0L, val takerBuyVolume: Double? = null)
data class StrategyResult(val signal: String, val score: Int, val entry: Double, val stop: Double, val target1: Double, val target2: Double, val reasons: List<String>, val smc: List<String>, val target3: Double = target2, val riskReward: List<Double> = listOf(1.5, 2.5, 3.5))

/** AURUM X 0.2.9 read-only engine: eight indicator families and 5m/15m/30m consensus. */
object TradingStrategy {
    private const val MIN_CANDLES = 60
    private val tfWeights = mapOf("5m" to .25, "15m" to .35, "30m" to .40)
    private val familyWeights = mapOf("trend" to 1.5, "momentum" to 1.3, "strength" to 1.1, "volume" to 1.0, "volatility" to .6, "structure" to 1.4, "divergence" to .8, "orderflow" to .7)
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
        if(c.size<MIN_CANDLES)return Report(false,"حداقل ۶۰ کندل لازم است")
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
        val e9=ema(v,9)!!;val e21=ema(v,21)!!;val e50=ema(v,50)!!
        val s20=sma(v,20);val s50=sma(v,50);val r=rsi(v);val mc=macd(v);val bands=bb(v)
        val st=stochRsi(v);val dx=adx(c);val ichi=ichimoku(c);val a=atr(c);val vol=volatility(c,timeframe,a);val vw=vwap(c);val ov=obvTrend(c);val mf=mfi(c)
        val sm=structure(c);val div=divergence(c)
        val regime=when{dx==null->"unknown";dx.value>=25->"trending";dx.value<18->"ranging";else->"transition"}
        val groups=linkedMapOf<String,MutableList<Pair<Double,Double>>>();val notes=mutableListOf<String>();val indicatorVotes=linkedMapOf<String,Int>()
        fun vote(group:String,name:String,value:Int,weight:Double=1.0){
            groups.getOrPut(group){mutableListOf()}.add(value.toDouble() to weight)
            indicatorVotes[name]=value
            if(value!=0)notes.add(name+":"+(if(value>0)"+" else "")+value)
        }
        vote("trend","EMA",if(e9>e21&&e21>e50)1 else if(e9<e21&&e21<e50)-1 else 0,1.2)
        vote("trend","SMA",if(s20!=null&&s50!=null&&price>s20&&s20>s50)1 else if(s20!=null&&s50!=null&&price<s20&&s20<s50)-1 else 0)
        vote("trend","Ichimoku",if(ichi!=null&&price>max(ichi.spanA,ichi.spanB)&&ichi.tenkan>ichi.kijun)1 else if(ichi!=null&&price<min(ichi.spanA,ichi.spanB)&&ichi.tenkan<ichi.kijun)-1 else 0,1.2)
        vote("momentum","RSI",if(r!=null&&r>52&&r<70)1 else if(r!=null&&r>30&&r<48)-1 else 0)
        vote("momentum","MACD",mc.third?.let{if(it>0)1 else if(it<0)-1 else 0}?:0,1.2)
        vote("momentum","StochRSI",if(st!=null&&st>55)1 else if(st!=null&&st<45)-1 else 0)
        vote("strength","ADX_DMI",if(regime=="trending"&&dx!!.plus>dx.minus)1 else if(regime=="trending"&&dx!!.minus>dx.plus)-1 else 0,1.2)
        vote("volume","VWAP",if(vw!=null&&price>vw)1 else if(vw!=null&&price<vw)-1 else 0)
        vote("volume","OBV_Trend",ov)
        vote("volume","VolumeImpulse",volumeImpulse(c,a),1.1)
        vote("volume","MFI",if(mf!=null&&mf>50&&mf<80)1 else if(mf!=null&&mf>20&&mf<50)-1 else 0)
        vote("volatility","Bollinger",if(bands!=null&&price>bands.second)1 else if(bands!=null&&price<bands.second)-1 else 0)
        val obTolerance=(a?:price*.001)*.25
        val inBullOb=sm.orderBlockType=="bullish"&&sm.orderBlockLow!=null&&sm.orderBlockHigh!=null&&price>=sm.orderBlockLow!!-obTolerance&&price<=sm.orderBlockHigh!!+obTolerance
        val inBearOb=sm.orderBlockType=="bearish"&&sm.orderBlockLow!=null&&sm.orderBlockHigh!=null&&price>=sm.orderBlockLow!!-obTolerance&&price<=sm.orderBlockHigh!!+obTolerance
        val smcBull=sm.event=="BOS_up"||sm.event=="CHoCH_up"||sm.sweep=="sell_side"||inBullOb
        val smcBear=sm.event=="BOS_down"||sm.event=="CHoCH_down"||sm.sweep=="buy_side"||inBearOb
        vote("structure","SMC",if(smcBull&&!smcBear)1 else if(smcBear&&!smcBull)-1 else 0,1.3)
        vote("divergence","Divergence",if(div=="bullish")1 else if(div=="bearish")-1 else 0,1.1)
        val flowRows=c.filter{it.takerBuyVolume!=null}
        val flowTotal=flowRows.sumOf{max(it.volume,0.0)}
        val flowBuy=flowRows.sumOf{max(it.takerBuyVolume?:0.0,0.0)}
        val flowSell=max(0.0,flowTotal-flowBuy)
        val takerVote=if(flowTotal>0.0&&flowBuy/flowTotal>=.55)1
            else if(flowTotal>0.0&&flowSell/flowTotal>=.55)-1 else 0
        vote("orderflow","TakerFlow",takerVote)
        val families=groups.mapValues{(_,items)->val den=items.sumOf{it.second};if(den==0.0)0.0 else items.sumOf{it.first*it.second}/den}
        val activeWeights=if(regime=="ranging")familyWeights+mapOf("trend" to .8,"structure" to 1.6,"divergence" to 1.2) else familyWeights
        val den=families.keys.sumOf{activeWeights[it]?:0.0}
        val score=if(den==0.0)0.0 else 100*families.entries.sumOf{it.value*(activeWeights[it.key]?:0.0)}/den
        val up=families.values.count{it>.20};val down=families.values.count{it<-.20}
        val bias=if(score>=22&&up>=4)"bullish" else if(score<=-22&&down>=4)"bearish" else "neutral"
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
        return Report(true,price=price,score=score,bias=bias,atr=a,ema21=e21,families=families,indicatorVotes=indicatorVotes,notes=notes,smc=smc,highVolatility=vol?.blocked?:false,atrPct=vol?.atrPct,volatilityRatio=vol?.ratio,marketRegime=regime,adxValue=dx?.value,structure=sm)
    }

    internal fun entryNotExtended(price:Double,ema21:Double?,atr:Double?,maxDistanceAtr:Double=.80):Boolean {
        if(ema21==null||atr==null||!price.isFinite()||!ema21.isFinite()||!atr.isFinite()||atr<=0.0)return false
        return abs(price-ema21)/atr<=maxDistanceAtr
    }

    internal fun noMajorIndicatorConflict(votes:Map<String,Int>,direction:Int):Boolean {
        val core=setOf("EMA","SMA","Ichimoku","RSI","MACD","StochRSI","ADX_DMI")
        val opposingCore=core.count{(votes[it]?:0)*direction<0}
        val opposingMinor=votes.count{(it.key !in core)&&it.value*direction<0}
        return opposingCore==0&&opposingMinor<=1
    }

    private fun directionalQuality(report:Report,direction:Double):Boolean {
        val families=report.families
        val aligned=families.values.count{it*direction>=.20}
        val opposing=families.values.count{it*direction<=-.20}
        val core=listOf("trend","momentum","strength")
        val coreClear=core.all{(families[it]?:0.0)*direction>=0.0}
        val side=if(direction>0)1 else -1
        return aligned>=5&&opposing<=1&&coreClear&&noMajorIndicatorConflict(report.indicatorVotes,side)
    }

    fun evaluate(five:List<MarketCandle>,fifteen:List<MarketCandle>,thirty:List<MarketCandle>,asOfMs:Long=System.currentTimeMillis(),expectancyStats:Map<String,List<Double>>?=null,enforceExpectancy:Boolean=true):StrategyResult {
        fun validSeries(rows:List<MarketCandle>,duration:Long):Boolean {
            if(rows.size<MIN_CANDLES||rows.any{it.time<=0L||it.time%duration!=0L})return false
            for(i in 1 until rows.size)if(rows[i].time<=rows[i-1].time||rows[i].time-rows[i-1].time!=duration)return false
            return true
        }
        val price=five.lastOrNull()?.close?:0.0
        if(!validSeries(five,300_000L)||!validSeries(fifteen,900_000L)||!validSeries(thirty,1_800_000L)){
            return StrategyResult("WAIT",0,price,price,price,price,
                listOf("داده یا زمان‌بندی یکی از تایم‌فریم‌های ۵، ۱۵ یا ۳۰ دقیقه نامعتبر/ناپیوسته است؛ سیگنال متوقف شد"),emptyList())
        }

        // The 5m close defines one immutable decision boundary. Higher-timeframe
        // candles are selected only if they had fully closed by that same boundary.
        val decisionTime=five.last().time+300_000L
        if(decisionTime>asOfMs||asOfMs-decisionTime>=300_000L){
            return StrategyResult("WAIT",0,price,price,price,price,
                listOf("کندل ۵ دقیقه‌ای بسته‌شده تازه و قابل‌همگام‌سازی نیست؛ سیگنال صادر نمی‌شود"),emptyList())
        }
        val expected15=(decisionTime/900_000L)*900_000L-900_000L
        val expected30=(decisionTime/1_800_000L)*1_800_000L-1_800_000L
        val closed15=fifteen.filter{it.time+900_000L<=decisionTime}
        val closed30=thirty.filter{it.time+1_800_000L<=decisionTime}
        if(closed15.size<MIN_CANDLES||closed30.size<MIN_CANDLES||
            closed15.last().time!=expected15||closed30.last().time!=expected30){
            return StrategyResult("WAIT",0,price,price,price,price,
                listOf("آخرین کندل بسته‌شده ۱۵ یا ۳۰ دقیقه با مرز تصمیم ۵ دقیقه همگام نیست؛ هیچ سیگنالی صادر نمی‌شود"),emptyList())
        }

        val reports=linkedMapOf("5m" to analyze(five,"5m"),"15m" to analyze(closed15,"15m"),"30m" to analyze(closed30,"30m"))
        val invalid=reports.filterValues{!it.valid}.keys
        if(invalid.isNotEmpty())return StrategyResult("WAIT",0,price,price,price,price,
            listOf("داده معتبر کافی نیست: "+invalid.joinToString("، ")),emptyList())

        val combined=reports.entries.sumOf{it.value.score*(tfWeights[it.key]?:0.0)}
        val volatilityBlocked=reports.filterValues{it.highVolatility}.keys
        if(volatilityBlocked.isNotEmpty())return StrategyResult("WAIT",combined.roundToInt(),price,price,price,price,
            listOf("فیلتر نوسان غیرعادی فعال شد؛ سیگنال متوقف شد: "+volatilityBlocked.joinToString("، ")),emptyList())
        val uncertainRegime=reports.filterValues{it.marketRegime!="trending"}.keys
        if(uncertainRegime.isNotEmpty())return StrategyResult("WAIT",combined.roundToInt(),price,price,price,price,
            listOf("بازار رنج، انتقالی یا فاقد روند تأییدشده است؛ سیگنال صادر نمی‌شود: "+uncertainRegime.joinToString("، ")),emptyList())

        // Hard three-way consensus: every timeframe must independently agree.
        // No majority vote, fallback, or 5m-only signal is permitted.
        val allBullish=reports.values.all{it.bias=="bullish"}
        val allBearish=reports.values.all{it.bias=="bearish"}
        val trigger5=reports["5m"]!!
        val sm5=trigger5.structure
        // Quality gate v1 mirrors the Python research engine: stronger 15m/30m
        // conviction, a stronger 5m trigger and a higher combined score.
        // These candidate thresholds require out-of-sample validation.
        val higherBullish=reports["15m"]!!.score>=26&&reports["30m"]!!.score>=26&&
            directionalQuality(reports["15m"]!!,1.0)&&directionalQuality(reports["30m"]!!,1.0)
        val higherBearish=reports["15m"]!!.score<=-26&&reports["30m"]!!.score<=-26&&
            directionalQuality(reports["15m"]!!,-1.0)&&directionalQuality(reports["30m"]!!,-1.0)
        val triggerBullish=directionalQuality(trigger5,1.0)
        val triggerBearish=directionalQuality(trigger5,-1.0)
        val rawSignal=when{
            allBullish&&higherBullish&&combined>=34&&trigger5.score>=24&&triggerBullish&&sm5?.retestLong==true->"LONG"
            allBearish&&higherBearish&&combined<=-34&&trigger5.score<=-24&&triggerBearish&&sm5?.retestShort==true->"SHORT"
            else->"WAIT"
        }
        val expectancy = if(rawSignal=="LONG"||rawSignal=="SHORT") ExpectancyPolicy.evaluate(expectancyStats?.get(rawSignal).orEmpty()) else null
        val signal = if(enforceExpectancy && rawSignal!="WAIT" && expectancy?.allowed!=true) "WAIT" else rawSignal
        val a=reports["5m"]?.atr?:0.0
        if(signal!="WAIT"&&a<=0.0)return StrategyResult("WAIT",combined.roundToInt(),price,price,price,price,
            listOf("ATR در دسترس نیست؛ صدور سیگنال متوقف شد"),emptyList())
        val entryDistance=sm5?.entryDistanceAtr
        if(signal!="WAIT"&&(entryDistance==null||entryDistance>.50)){
            return StrategyResult("WAIT",combined.roundToInt(),price,price,price,price,
                listOf("فاصله Retest نامشخص یا بیش از ۰٫۵ ATR است؛ ورود دیرهنگام رد شد"),reports.values.flatMap{it.smc}.distinct())
        }
        if(signal!="WAIT"&&!entryNotExtended(price,trigger5.ema21,a)){
            return StrategyResult("WAIT",combined.roundToInt(),price,price,price,price,
                listOf("فاصله ورود از EMA21 بیش از ۰٫۸ ATR یا داده EMA/ATR نامعتبر است؛ ورود تعقیبی رد شد"),reports.values.flatMap{it.smc}.distinct())
        }
        if(signal!="WAIT"&&sm5?.falseBreakout==true){
            return StrategyResult("WAIT",combined.roundToInt(),price,price,price,price,
                listOf("شکست جعلی شناسایی شد؛ ساختار فعلی نامعتبر است و باید منتظر ساختار تازه ماند"),reports.values.flatMap{it.smc}.distinct())
        }
        val atrRisk=max(a*1.5,price*.001)
        val structuralStop=if(signal=="LONG")sm5?.previousSwingLow?.minus(a*.15) else sm5?.previousSwingHigh?.plus(a*.15)
        val candidate=structuralStop?:if(signal=="LONG")price-atrRisk else price+atrRisk
        val structuralRisk=abs(price-candidate)
        val risk=if(candidate>0&&(if(signal=="LONG")candidate<price else candidate>price)&&structuralRisk<=3.0*a&&structuralRisk>=a*.25)structuralRisk else atrRisk
        val stop=if(signal=="LONG")price-risk else if(signal=="SHORT")price+risk else price
        val tp1=if(signal=="LONG")price+1.5*risk else if(signal=="SHORT")price-1.5*risk else price
        val tp2=if(signal=="LONG")price+2.5*risk else if(signal=="SHORT")price-2.5*risk else price
        val tp3=if(signal=="LONG")price+3.5*risk else if(signal=="SHORT")price-3.5*risk else price
        val reasons=mutableListOf("موتور تحلیل نسخه ۰.۳.۰: هشت خانواده شاخص",
            "امتیاز ترکیبی سه تایم‌فریم: "+"%.2f".format(combined))
        for((tf,rep) in reports){
            reasons.add(tf+": امتیاز تحلیل "+"%.2f".format(rep.score))
            reasons.add("امتیاز خانواده‌ها: "+rep.families.entries.joinToString("، "){it.key+"="+"%.2f".format(it.value)})
        }
        if(signal=="LONG"||signal=="SHORT"){
            reasons.add("تأیید مستقل و هم‌زمان هر سه تایم‌فریم ۵، ۱۵ و ۳۰ دقیقه")
            reasons.add("نقطه ورود: بازگشت و تثبیت مجدد روی سطح شکست در ۵ دقیقه")
            reasons.add(if(signal=="LONG")"تأیید کامل سه تایم‌فریم؛ لانگ" else "تأیید کامل سه تایم‌فریم؛ شورت")
            reasons.add("امید ریاضی خالص معاملات بسته‌شده تأیید شد؛ حداقل ۳۰ معامله، حد پایین اطمینان ۹۵٪ مثبت و Profit Factor بالاتر از ۱٫۱۰")
        } else if(rawSignal=="LONG"||rawSignal=="SHORT") {
            reasons.add("ورود متوقف شد: امید ریاضی خالص این جهت هنوز با حداقل ۳۰ معامله بسته‌شده اثبات نشده است")
        }
        val smc=reports.flatMap{(tf,rep)->rep.smc.map{tf+": "+it}}.distinct()
        return StrategyResult(signal,combined.roundToInt(),price,stop,tp1,tp2,reasons,smc,tp3,listOf(1.5,2.5,3.5))
    }
}