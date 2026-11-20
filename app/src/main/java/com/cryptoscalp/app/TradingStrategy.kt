package com.cryptoscalp.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

data class MarketCandle(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double = 0.0, val time: Long = 0L)
data class StrategyResult(val signal: String, val score: Int, val entry: Double, val stop: Double, val target1: Double, val target2: Double, val reasons: List<String>, val smc: List<String>)

/** AURUM X 0.2.9 read-only engine: eight indicator families and 5m/15m/30m consensus. */
object TradingStrategy {
    private const val MIN_CANDLES = 60
    private val tfWeights = mapOf("5m" to .25, "15m" to .35, "30m" to .40)
    private val familyWeights = mapOf("trend" to 1.5, "momentum" to 1.3, "strength" to 1.1, "volume" to 1.0, "volatility" to .6, "structure" to 1.4, "divergence" to .8, "orderflow" to .7)
    private data class ADX(val value: Double, val plus: Double, val minus: Double)
    private data class Ichi(val tenkan: Double, val kijun: Double, val spanA: Double, val spanB: Double)
    private data class Structure(val event: String, val sweep: String)
    private data class Report(val valid: Boolean, val reason: String = "", val price: Double = 0.0, val score: Double = 0.0, val bias: String = "neutral", val atr: Double? = null, val families: Map<String,Double> = emptyMap(), val notes: List<String> = emptyList(), val smc: List<String> = emptyList())

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
    private fun macd(v: List<Double>): Triple<Double?,Double?,Double?> {
        if (v.size<35) return Triple(null,null,null)
        val vals=(26..v.size).mapNotNull { end ->
            val f=ema(v.take(end),12); val s=ema(v.take(end),26)
            if (f==null||s==null) null else f-s
        }
        val line=(ema(v,12)?:return Triple(null,null,null))-(ema(v,26)?:return Triple(null,null,null))
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
        val rs=(n+1..v.size).mapNotNull{rsi(v.take(it),n)}
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
    private fun obv(c:List<MarketCandle>):Double? {
        if(c.size<2)return null
        var x=0.0
        for(i in 1 until c.size)x+=when{c[i].close>c[i-1].close->c[i].volume;c[i].close<c[i-1].close->-c[i].volume;else->0.0}
        return x
    }
    private fun mfi(c:List<MarketCandle>,n:Int=14):Double? {
        if(c.size<n+1)return null
        fun tp(x:MarketCandle)=(x.high+x.low+x.close)/3
        var pos=0.0;var neg=0.0
        for(i in c.size-n until c.size){val flow=tp(c[i])*max(c[i].volume,0.0);if(tp(c[i])>tp(c[i-1]))pos+=flow else if(tp(c[i])<tp(c[i-1]))neg+=flow}
        return if(neg==0.0)100.0 else 100-100/(1+pos/neg)
    }
    private fun divergence(c:List<MarketCandle>,lookback:Int=30):String {
        if(c.size<lookback+15)return "none"
        val v=c.map{it.close};val rs=(v.size-lookback-14..v.size).mapNotNull{rsi(v.take(it))}
        if(rs.size<lookback+1)return "none"
        val old=v.subList(v.size-lookback-14,v.size-14);val recent=v.takeLast(14)
        return when{recent.minOrNull()!!<old.minOrNull()!!&&rs.last()>rs.first()+3->"bullish";recent.maxOrNull()!!>old.maxOrNull()!!&&rs.last()<rs.first()-3->"bearish";else->"none"}
    }
    private fun structure(c:List<MarketCandle>,n:Int=20):Structure {
        if(c.size<n+2)return Structure("none","none")
        val prev=c.subList(c.size-n-1,c.size-1);val last=c.last();val hi=prev.maxOf{it.high};val lo=prev.minOf{it.low}
        val event=if(last.close>hi)"BOS_up" else if(last.close<lo)"BOS_down" else "none"
        val sweep=if(last.low<lo&&last.close>lo)"sell_side" else if(last.high>hi&&last.close<hi)"buy_side" else "none"
        return Structure(event,sweep)
    }

    private fun analyze(c:List<MarketCandle>):Report {
        if(c.size<MIN_CANDLES)return Report(false,"حداقل ۶۰ کندل لازم است")
        if(c.takeLast(MIN_CANDLES).any{!it.open.isFinite()||!it.high.isFinite()||!it.low.isFinite()||!it.close.isFinite()||!it.volume.isFinite()||it.open<=0||it.high<=0||it.low<=0||it.close<=0||it.high<it.low})
            return Report(false,"داده کندل نامعتبر است")
        val v=c.map{it.close};val price=v.last()
        val e9=ema(v,9)!!;val e21=ema(v,21)!!;val e50=ema(v,50)!!
        val s20=sma(v,20);val s50=sma(v,50);val r=rsi(v);val mc=macd(v);val bands=bb(v)
        val st=stochRsi(v);val dx=adx(c);val ichi=ichimoku(c);val a=atr(c);val vw=vwap(c);val ov=obv(c);val mf=mfi(c)
        val sm=structure(c);val div=divergence(c)
        val groups=linkedMapOf<String,MutableList<Pair<Double,Double>>>();val notes=mutableListOf<String>()
        fun vote(group:String,name:String,value:Int,weight:Double=1.0){
            groups.getOrPut(group){mutableListOf()}.add(value.toDouble() to weight)
            if(value!=0)notes.add(name+":"+(if(value>0)"+" else "")+value)
        }
        vote("trend","EMA",if(e9>e21&&e21>e50)1 else if(e9<e21&&e21<e50)-1 else 0,1.2)
        vote("trend","SMA",if(s20!=null&&s50!=null&&price>s20&&s20>s50)1 else if(s20!=null&&s50!=null&&price<s20&&s20<s50)-1 else 0)
        vote("trend","Ichimoku",if(ichi!=null&&price>max(ichi.spanA,ichi.spanB)&&ichi.tenkan>ichi.kijun)1 else if(ichi!=null&&price<min(ichi.spanA,ichi.spanB)&&ichi.tenkan<ichi.kijun)-1 else 0,1.2)
        vote("momentum","RSI",if(r!=null&&r>52&&r<70)1 else if(r!=null&&r>30&&r<48)-1 else 0)
        vote("momentum","MACD",mc.third?.let{if(it>0)1 else if(it<0)-1 else 0}?:0,1.2)
        vote("momentum","StochRSI",if(st!=null&&st>55)1 else if(st!=null&&st<45)-1 else 0)
        vote("strength","ADX_DMI",if(dx!=null&&dx.value>=20&&dx.plus>dx.minus)1 else if(dx!=null&&dx.value>=20&&dx.minus>dx.plus)-1 else 0,1.2)
        vote("volume","VWAP",if(vw!=null&&price>vw)1 else if(vw!=null&&price<vw)-1 else 0)
        vote("volume","OBV",if(ov!=null&&ov>0)1 else if(ov!=null&&ov<0)-1 else 0)
        vote("volume","MFI",if(mf!=null&&mf>50&&mf<80)1 else if(mf!=null&&mf>20&&mf<50)-1 else 0)
        vote("volatility","Bollinger",if(bands!=null&&price>bands.second)1 else if(bands!=null&&price<bands.second)-1 else 0)
        vote("structure","SMC",if(sm.event=="BOS_up"||sm.sweep=="sell_side")1 else if(sm.event=="BOS_down"||sm.sweep=="buy_side")-1 else 0,1.3)
        vote("divergence","Divergence",if(div=="bullish")1 else if(div=="bearish")-1 else 0,1.1)
        // Toobit does not currently expose taker_buy_volume in this candle response.
        vote("orderflow","TakerFlow",0)
        val families=groups.mapValues{(_,items)->val den=items.sumOf{it.second};if(den==0.0)0.0 else items.sumOf{it.first*it.second}/den}
        val den=families.keys.sumOf{familyWeights[it]?:0.0}
        val score=if(den==0.0)0.0 else 100*families.entries.sumOf{it.value*(familyWeights[it.key]?:0.0)}/den
        val up=families.values.count{it>.20};val down=families.values.count{it<-.20}
        val bias=if(score>=22&&up>=4)"bullish" else if(score<=-22&&down>=4)"bearish" else "neutral"
        val smc=mutableListOf<String>()
        if(sm.event=="BOS_up")smc.add("BOS صعودی")
        if(sm.event=="BOS_down")smc.add("BOS نزولی")
        if(sm.sweep=="sell_side")smc.add("جمع‌آوری نقدینگی کف")
        if(sm.sweep=="buy_side")smc.add("جمع‌آوری نقدینگی سقف")
        return Report(true,price=price,score=score,bias=bias,atr=a,families=families,notes=notes,smc=smc)
    }

    fun evaluate(five:List<MarketCandle>,fifteen:List<MarketCandle>,thirty:List<MarketCandle>):StrategyResult {
        val reports=linkedMapOf("5m" to analyze(five),"15m" to analyze(fifteen),"30m" to analyze(thirty))
        val invalid=reports.filterValues{!it.valid}.keys
        val price=reports["5m"]?.price?:five.lastOrNull()?.close?:0.0
        if(invalid.isNotEmpty())return StrategyResult("WAIT",0,price,price,price,price,listOf("داده معتبر کافی نیست: "+invalid.joinToString("، ")),emptyList())
        val combined=reports.entries.sumOf{it.value.score*(tfWeights[it.key]?:0.0)}
        val longs=reports.values.count{it.bias=="bullish"};val shorts=reports.values.count{it.bias=="bearish"}
        val signal=when{combined>=28&&longs==3->"LONG";combined<=-28&&shorts==3->"SHORT";else->"WAIT"}
        val a=reports["5m"]?.atr?:0.0
        if(signal!="WAIT"&&a<=0.0)return StrategyResult("WAIT",combined.roundToInt(),price,price,price,price,listOf("ATR در دسترس نیست؛ صدور سیگنال متوقف شد"),emptyList())
        val risk=max(a*1.5,price*.001)
        val stop=when(signal){"LONG"->price-risk;"SHORT"->price+risk;else->price}
        val tp1=when(signal){"LONG"->price+risk;"SHORT"->price-risk;else->price}
        val tp2=when(signal){"LONG"->price+2*risk;"SHORT"->price-2*risk;else->price}
        val reasons=mutableListOf("موتور تحلیل نسخه ۰.۲.۹: هشت خانواده شاخص","امتیاز ترکیبی سه تایم‌فریم: "+"%.2f".format(combined))
        for((tf,rep) in reports){
            reasons.add(tf+": "+rep.bias+"، امتیاز "+"%.2f".format(rep.score))
            reasons.add("امتیاز خانواده‌ها: "+rep.families.entries.joinToString("، "){it.key+"="+"%.2f".format(it.value)})
        }
        reasons.add(if(signal=="LONG")"تأیید کامل سه تایم‌فریم؛ لانگ" else if(signal=="SHORT")"تأیید کامل سه تایم‌فریم؛ شورت" else "اجماع سه تایم‌فریم کافی نیست؛ معامله‌ای پیشنهاد نمی‌شود")
        val smc=reports.flatMap{(tf,rep)->rep.smc.map{tf+": "+it}}.distinct()
        return StrategyResult(signal,combined.roundToInt(),price,stop,tp1,tp2,reasons,smc)
    }
}
