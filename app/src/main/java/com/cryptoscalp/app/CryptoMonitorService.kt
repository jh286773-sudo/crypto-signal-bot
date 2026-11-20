package com.cryptoscalp.app

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class CryptoMonitorService : Service() {
    private val handler=Handler(Looper.getMainLooper())
    private val serviceChannel="monitor_service"
    private val alertChannel="scalp_signals"
    private val prefs by lazy { getSharedPreferences("crypto_monitor",Context.MODE_PRIVATE) }
    @Volatile private var scanning=false
    private var networkCallback:ConnectivityManager.NetworkCallback?=null
    private val loop=object:Runnable{override fun run(){scan();handler.postDelayed(this,60_000L)}}

    override fun onCreate(){
        super.onCreate();createChannels()
        val n=serviceNotification("پایش خودکار بازار فعال است")
        if(Build.VERSION.SDK_INT>=34) startForeground(9100,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(9100,n)
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb=object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:Network){handler.post{updateStatus("اینترنت وصل شد؛ تحلیل از سر گرفته شد");scan()}}
            override fun onLost(network:Network){handler.post{updateStatus("اینترنت قطع است؛ منتظر اتصال مجدد")}}
        }
        networkCallback=cb
        try{cm.registerDefaultNetworkCallback(cb)}catch(_:Exception){}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){handler.removeCallbacks(loop);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
        handler.removeCallbacks(loop);scan();handler.postDelayed(loop,60_000L);return START_STICKY
    }
    override fun onDestroy(){
        handler.removeCallbacksAndMessages(null)
        networkCallback?.let{try{(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)}catch(_:Exception){}}
        super.onDestroy()
    }
    override fun onBind(intent:Intent?)=null
    private fun online():Boolean{
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n=cm.activeNetwork?:return false
        val c=cm.getNetworkCapabilities(n)?:return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun symbols():List<String>{
        val selected=prefs.getStringSet("selected_symbols",emptySet())?.toList().orEmpty()
        val open=prefs.all.keys.filter{it.startsWith("open_setup_")}.map{it.removePrefix("open_setup_")}
        return (selected+open).distinct().take(100)
    }
    private fun scan(){
        if(scanning)return
        if(!online()){updateStatus("اینترنت قطع است؛ منتظر اتصال");return}
        val list=symbols()
        if(list.isEmpty()){updateStatus("هیچ ارزی انتخاب نشده؛ از داخل برنامه نمادها را دستی انتخاب کن.");return}
        val exchange = "Toobit"
        scanning=true
        updateStatus("پایش قیمت‌ها از توبیت • بررسی ریسک هر ۶۰ ثانیه")
        thread(name="market-monitor"){
            var ok=0
            var failed=0
            var analyzed=0
            var firstError=""
            val now=System.currentTimeMillis()
            for(symbol in list)try{
                // Keep a tracked position open until an opposite confirmed signal arrives.
                // No price-based SL/TP exit is used in signal-reversal hold mode.

                // Strategy runs only when a newer fully closed 5m candle is available.
                // Capture one exchange-clock boundary and use it for all three HTTP candle requests.
                val marketAsOfMs=ExchangeMarketData.exchangeTimeMs()
                val five=ExchangeMarketData.candles(exchange,symbol,"5m",marketAsOfMs)
                val latestBar=five.last()
                manageOpen(symbol,latestBar)
                manageShadow(symbol,latestBar)
                val latestClosedCandleTime=latestBar.time
                val key="last_analysis_candle_$symbol"
                val previousCandleTime=prefs.getLong(key,-1L)
                if(previousCandleTime<0L){
                    // First launch establishes a baseline instead of issuing a delayed historical signal.
                    prefs.edit().putLong(key,latestClosedCandleTime).apply()
                } else if(latestClosedCandleTime>previousCandleTime){
                    val fifteen=ExchangeMarketData.candles(exchange,symbol,"15m",marketAsOfMs)
                    val thirty=ExchangeMarketData.candles(exchange,symbol,"30m",marketAsOfMs)
                    val candidate=TradingStrategy.evaluate(five,fifteen,thirty,marketAsOfMs,emptyMap(),false)
                    if(isDirectionalSignal(candidate.signal) && prefs.getString("shadow_setup_$symbol",null)==null) saveShadow(symbol,candidate)
                    val r=TradingStrategy.evaluate(five,fifteen,thirty,marketAsOfMs,loadExpectancyStats(symbol),true)
                    val open=prefs.getString("open_setup_$symbol",null)
                    val openSide=open?.let { JSONObject(it).optString("side") }
                    val previous=prefs.getString("last_signal_$symbol",null)
                    val lossCooldownActive = isLossCooldownActive(symbol,System.currentTimeMillis())
                    when (SignalDeduplicationPolicy.decide(openSide,r.signal,previous,lossCooldownActive)) {
                        SignalDecision.OPEN -> {
                            saveOpen(symbol,r)
                            notifySignal(symbol,r)
                        }
                        SignalDecision.REVERSE -> {
                            recordVirtualClose(symbol,r.entry)
                            clearOpen(symbol)
                            saveOpen(symbol,r)
                            notifyReversal(symbol,openSide.orEmpty(),r)
                        }
                        SignalDecision.CLOSE_ONLY -> {
                            recordVirtualClose(symbol,r.entry)
                            clearOpen(symbol)
                            notifyExit(symbol,"بستن موقعیت قبلی • $symbol",
                                "سیگنال مخالف $r.signal تأیید شد؛ موقعیت $openSide بسته شد. به‌دلیل دوره توقف ریسک، موقعیت جدید باز نشد.")
                        }
                        SignalDecision.NONE -> Unit
                    }
                    // WAIT remains internal; a cooldown reversal is reconsidered after its expiry.
                    prefs.edit().putString("last_signal_$symbol",if(lossCooldownActive)"WAIT" else r.signal)
                        .putLong(key,latestClosedCandleTime).apply()
                    analyzed++
                }
                ok++
            }catch(e:Exception){
                failed++
                if(firstError.isBlank()) firstError="$symbol: ${e.message ?: e.javaClass.simpleName}"
            }
            scanning=false
            handler.post{
                val detail=if(failed>0) " • خطا $failed: $firstError" else ""
                updateStatus("توبیت • پایش $ok از ${list.size} نماد • تحلیل جدید: $analyzed • پایش قیمت هر ۶۰ ثانیه$detail")
            }
        }
    }
    // WAIT is an internal no-trade state only; user-facing entry alerts are LONG or SHORT.
    private fun isDirectionalSignal(signal:String)=signal=="LONG"||signal=="SHORT"

    private fun saveOpen(symbol:String,r:StrategyResult){
        val o=JSONObject().put("side",r.signal).put("entry",r.entry).put("stop",r.stop).put("tp1",r.target1).put("tp2",r.target2).put("tp3",r.target3).put("tp1Done",false).put("tp2Done",false).put("openedAt",System.currentTimeMillis())
        prefs.edit().putString("open_setup_$symbol",o.toString()).apply()
    }
    private fun expectancyKey(symbol:String,side:String)="shadow_closed_net_r_"+symbol+"_"+side
    private fun loadExpectancyStats(symbol:String):Map<String,List<Double>> = listOf("LONG","SHORT").associateWith { side ->
        prefs.getString(expectancyKey(symbol,side),"").orEmpty().split(",").mapNotNull{it.toDoubleOrNull()}.takeLast(200)
    }
    private fun saveShadow(symbol:String,r:StrategyResult){
        val risk=kotlin.math.abs(r.entry-r.stop); if(risk<=0.0||!risk.isFinite())return
        val o=JSONObject().put("side",r.signal).put("entry",r.entry).put("initialRisk",risk).put("stop",r.stop).put("tp1",r.target1).put("tp2",r.target2).put("tp3",r.target3).put("remaining",1.0).put("grossR",0.0).put("entryCostR",r.entry*.0007/risk).put("tp1Done",false).put("tp2Done",false)
        prefs.edit().putString("shadow_setup_$symbol",o.toString()).apply()
    }
    private fun recordShadowClose(symbol:String,o:JSONObject,exit:Double,fraction:Double){
        val side=o.optString("side"); val entry=o.optDouble("entry",0.0); val risk=o.optDouble("initialRisk",0.0)
        if((side!="LONG"&&side!="SHORT")||entry<=0.0||risk<=0.0||exit<=0.0||fraction<=0.0)return
        val dir=if(side=="LONG")1.0 else -1.0
        o.put("grossR",o.optDouble("grossR",0.0)+dir*(exit-entry)/risk*fraction)
        o.put("exitCostR",o.optDouble("exitCostR",0.0)+exit*.0007/risk*fraction)
        o.put("remaining",maxOf(0.0,o.optDouble("remaining",1.0)-fraction))
        if(o.optDouble("remaining",0.0)<=1e-8){
            val net=o.optDouble("grossR",0.0)-o.optDouble("entryCostR",0.0)-o.optDouble("exitCostR",0.0)
            if(net.isFinite()){val key=expectancyKey(symbol,side);val old=prefs.getString(key,"").orEmpty().split(",").mapNotNull{it.toDoubleOrNull()}.takeLast(199);prefs.edit().putString(key,(old+net).joinToString(",")).remove("shadow_setup_$symbol").apply()}
            else prefs.edit().remove("shadow_setup_$symbol").apply()
        }else prefs.edit().putString("shadow_setup_$symbol",o.toString()).apply()
    }
    private fun manageShadow(symbol:String,bar:MarketCandle){
        val raw=prefs.getString("shadow_setup_$symbol",null)?:return
        val o=try{JSONObject(raw)}catch(_:Exception){prefs.edit().remove("shadow_setup_$symbol").apply();return}
        val long=o.optString("side")=="LONG"; val stop=o.optDouble("stop",0.0)
        val stopped=if(long)bar.low<=stop else bar.high>=stop
        if(stopped){val fill=if(long)minOf(bar.open,stop) else maxOf(bar.open,stop);recordShadowClose(symbol,o,fill,o.optDouble("remaining",1.0));return}
        for(i in 0..2){
            if((i==0&&o.optBoolean("tp1Done"))||(i==1&&o.optBoolean("tp2Done")))continue
            val target=o.optDouble("tp"+(i+1),0.0); val hit=if(long)bar.high>=target else bar.low<=target
            if(!hit)break
            val rem=o.optDouble("remaining",0.0); val fraction=if(i==2)rem else minOf(rem,1.0/3.0)
            val fill=if(long)maxOf(bar.open,target) else minOf(bar.open,target)
            if(i==0){o.put("tp1Done",true);o.put("stop",o.optDouble("entry"))}
            if(i==1){o.put("tp2Done",true);o.put("stop",o.optDouble("tp1"))}
            recordShadowClose(symbol,o,fill,fraction)
            if(prefs.getString("shadow_setup_$symbol",null)==null||i<2)return
        }
    }
    private fun recordVirtualClose(symbol:String,exit:Double){
        val raw=prefs.getString("open_setup_$symbol",null)?:return
        val o=try{JSONObject(raw)}catch(_:Exception){return}
        val side=o.optString("side");val entry=o.optDouble("entry",0.0);val stop=o.optDouble("stop",0.0)
        val risk=kotlin.math.abs(entry-stop)
        if((side!="LONG"&&side!="SHORT")||entry<=0.0||risk<=0.0||exit<=0.0)return
        val direction=if(side=="LONG")1.0 else -1.0
        val grossR=direction*(exit-entry)/risk
        // Conservative modeled round-trip fee + slippage: 10 bps each side.
        val costsR=(entry+exit)*0.001/risk
        val netR=grossR-costsR
        val key=expectancyKey(symbol,side)
        val prior=prefs.getString(key,"").orEmpty().split(",").mapNotNull{it.toDoubleOrNull()}.takeLast(199)
        prefs.edit().putString(key,(prior+netR).joinToString(",")).apply()
    }
    private fun manageOpen(symbol:String,bar:MarketCandle){
        val price=bar.close
        val raw=prefs.getString("open_setup_$symbol",null)?:return
        val s=try{JSONObject(raw)}catch(_:Exception){prefs.edit().remove("open_setup_$symbol").apply();return}
        val long=s.optString("side")=="LONG"
        // Migrate setups saved by older app versions, which tracked only TP1/TP2.
        if(!s.has("tp3"))s.put("tp3",s.optDouble("tp2"))
        if(!s.has("tp2Done"))s.put("tp2Done",false)
        val stop=if(long)bar.low<=s.optDouble("stop") else bar.high>=s.optDouble("stop")
        if(stop){
            val protectedByTarget = s.optBoolean("tp1Done")
            val cooldownStarted = if(protectedByTarget) {
                clearLossHistory(symbol)
                false
            } else {
                registerFullStop(symbol,System.currentTimeMillis())
            }
            val detail = if(cooldownStarted)
                " • پس از دو حدضرر کامل در ۲۴ ساعت، سیگنال‌های جدید این نماد تا ۲۴ ساعت متوقف شدند"
            else if(protectedByTarget)
                " • خروج پس از فعال‌شدن حد سود اول؛ در شمارش ضرر کامل لحاظ نشد"
            else ""
            val stopLevel=s.optDouble("stop")
            val stopFill=if(long)minOf(bar.open,stopLevel) else maxOf(bar.open,stopLevel)
            closeOpen(symbol,"حد ضرر فعال شد • قیمت ${fmt(stopFill)}$detail")
            return
        }
        val tp1=if(long)bar.high>=s.optDouble("tp1") else bar.low<=s.optDouble("tp1")
        if(tp1&&!s.optBoolean("tp1Done")){
            // After TP1, protect the remaining setup at breakeven. This is an
            // app-side alert level only; no stop/partial-close order is sent.
            s.put("tp1Done",true)
            val entryPrice=s.optDouble("entry",0.0)
            if(entryPrice>0.0)s.put("stop",entryPrice)
            prefs.edit().putString("open_setup_$symbol",s.toString()).apply()
            notifyExit(symbol,"مرحله اول حد سود لمس شد • حد ضرر به نقطه ورود منتقل شد",
                "قیمت فعلی: ${fmt(price)} • حد ضرر جدید: ${fmt(s.optDouble("stop"))} • هدف دوم: ${fmt(s.optDouble("tp2"))} • فقط یادآوری برنامه؛ سفارش خودکار اجرا نمی‌شود")
        }
        val tp2=if(long)bar.high>=s.optDouble("tp2") else bar.low<=s.optDouble("tp2")
        if(tp2&&!s.optBoolean("tp2Done")){
            s.put("tp2Done",true)
            // Lock TP1 for the remaining setup after TP2; this is notification state only.
            val lockedStop=s.optDouble("tp1",s.optDouble("entry"))
            s.put("stop",lockedStop)
            prefs.edit().putString("open_setup_$symbol",s.toString()).apply()
            notifyExit(symbol,"مرحله دوم حد سود لمس شد • سود مرحله اول محافظت شد",
                "قیمت فعلی: ${fmt(price)} • حد ضرر حفاظتی جدید: ${fmt(lockedStop)} • هدف سوم: ${fmt(s.optDouble("tp3"))} • فقط یادآوری برنامه؛ سفارش خودکار اجرا نمی‌شود")
        }
        val tp3=if(long)bar.high>=s.optDouble("tp3") else bar.low<=s.optDouble("tp3")
        if(tp3){
            clearLossHistory(symbol)
            val targetFill=if(long)maxOf(bar.open,s.optDouble("tp3")) else minOf(bar.open,s.optDouble("tp3"))
            closeOpen(symbol,"حد سود سوم فعال شد • قیمت ${fmt(targetFill)}")
        }
    }
    private fun lossTimesKey(symbol:String)="full_stop_times_$symbol"
    private fun lossCooldownKey(symbol:String)="loss_cooldown_until_$symbol"
    private fun readLossTimes(symbol:String):List<Long> =
        prefs.getString(lossTimesKey(symbol),"").orEmpty().split(",").mapNotNull{it.toLongOrNull()}

    private fun registerFullStop(symbol:String,nowMs:Long):Boolean{
        val update=LossCooldownPolicy.recordFullStop(readLossTimes(symbol),nowMs)
        prefs.edit().putString(lossTimesKey(symbol),update.recentLosses.joinToString(","))
            .putLong(lossCooldownKey(symbol),update.cooldownUntilMs).apply()
        return LossCooldownPolicy.isActive(update.cooldownUntilMs,nowMs)
    }
    private fun isLossCooldownActive(symbol:String,nowMs:Long):Boolean{
        val until=prefs.getLong(lossCooldownKey(symbol),0L)
        if(LossCooldownPolicy.isActive(until,nowMs))return true
        if(until>0L)prefs.edit().remove(lossCooldownKey(symbol)).remove(lossTimesKey(symbol)).apply()
        return false
    }
    private fun clearLossHistory(symbol:String){
        prefs.edit().remove(lossTimesKey(symbol)).remove(lossCooldownKey(symbol)).apply()
    }
    private fun clearOpen(symbol:String){prefs.edit().remove("open_setup_$symbol").apply()}
    private fun closeOpen(symbol:String,reason:String){ val px=Regex("قیمت ([0-9.eE+-]+)").find(reason)?.groupValues?.getOrNull(1)?.toDoubleOrNull(); if(px!=null)recordVirtualClose(symbol,px); clearOpen(symbol); notifyExit(symbol,"خروج از موقعیت • $symbol",reason) }
    private fun fmt(v:Double)=if(v>=100)"%.2f".format(v) else if(v>=1)"%.4f".format(v) else "%.7f".format(v)
    private fun notifySignal(symbol:String,r:StrategyResult){
        val body=buildString{
            append("اقدام: بازکردن موقعیت ${r.signal} • ${if(r.signal=="LONG")"لانگ" else "شورت"}\\n")
            append("تا زمان دریافت سیگنال مخالف معتبر، موقعیت باز نگه داشته می‌شود.\\n")
            append("امتیاز تأیید: ${r.score}\\n")
            append("این اعلان راهنمای دستی است؛ برنامه سفارش صرافی را ثبت یا اجرا نمی‌کند.\\n")
            append("دلایل: ${(r.reasons+r.smc).joinToString("، ")}")
        }
        val b=notificationBuilder(alertChannel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("بازکردن ${r.signal} • $symbol").setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(symbol.hashCode(),b.build())
    }
    private fun notifyReversal(symbol:String,oldSide:String,r:StrategyResult){
        val body=buildString{
            append("۱) بستن موقعیت قبلی: $oldSide\\n")
            append("۲) بازکردن موقعیت جدید: ${r.signal}\\n")
            append("سیگنال مخالف پس از بسته‌شدن کندل و تأیید تایم‌فریم‌ها صادر شد.\\n")
            append("این اعلان راهنمای دستی است؛ برنامه سفارش صرافی را ثبت یا اجرا نمی‌کند.\\n")
            append("دلایل: ${(r.reasons+r.smc).joinToString("، ")}")
        }
        val b=notificationBuilder(alertChannel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("تغییر جهت • $symbol").setContentText("بستن $oldSide ← بازکردن ${r.signal}")
            .setStyle(Notification.BigTextStyle().bigText(body))
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(symbol.hashCode(),b.build())
    }
    private fun notifyExit(symbol:String,title:String,body:String){
        val b=Notification.Builder(this,alertChannel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(symbol.hashCode() xor title.hashCode(),b.build())
    }
    private fun createChannels(){
        if(Build.VERSION.SDK_INT>=26){
            val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(serviceChannel,"وضعیت پایش",NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(alertChannel,"هشدار سیگنال‌ها",NotificationManager.IMPORTANCE_HIGH))
        }
    }
    private fun notificationBuilder(channel:String):Notification.Builder = if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,channel) else Notification.Builder(this)
    private fun serviceNotification(message:String)=notificationBuilder(serviceChannel).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Crypto Scalp • پایش فعال").setContentText(message).setOngoing(true).build()
    private fun updateStatus(message:String){(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9100,serviceNotification(message))}
    companion object{
        const val ACTION_START="com.cryptoscalp.app.START_MONITOR"
        const val ACTION_STOP="com.cryptoscalp.app.STOP_MONITOR"
    }
}
