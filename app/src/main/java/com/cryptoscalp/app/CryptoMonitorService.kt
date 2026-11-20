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
                // Open positions are not evaluated against price exits; only opposite signals close them.
                val hadShadowAtBarStart=prefs.getString("shadow_setup_$symbol",null)!=null
                // Shadow positions also follow signal-reversal exits only.
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
                    closeShadowOnReversal(symbol,candidate.signal,candidate.entry)
                    if(!hadShadowAtBarStart && isDirectionalSignal(candidate.signal) && prefs.getString("shadow_setup_$symbol",null)==null) saveShadow(symbol,candidate,latestClosedCandleTime)
                    val r=TradingStrategy.evaluate(five,fifteen,thirty,marketAsOfMs,loadExpectancyStats(symbol),true)
                    val open=prefs.getString("open_setup_$symbol",null)
                    val openSide=open?.let { JSONObject(it).optString("side") }
                    val previous=prefs.getString("last_signal_$symbol",null)
                    when (SignalDeduplicationPolicy.decide(openSide,r.signal,previous,false)) {
                        SignalDecision.OPEN -> {
                            saveOpen(symbol,r,latestClosedCandleTime)
                            notifySignal(symbol,r)
                        }
                        SignalDecision.REVERSE -> {
                            recordVirtualClose(symbol,r.entry)
                            clearOpen(symbol)
                            saveOpen(symbol,r,latestClosedCandleTime)
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
                    prefs.edit().putString("last_signal_$symbol",r.signal)
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

    private fun saveOpen(symbol:String,r:StrategyResult,barTime:Long){
        val o=JSONObject().put("side",r.signal).put("entry",r.entry)
            .put("openedAt",System.currentTimeMillis()).put("openedBar",barTime)
        prefs.edit().putString("open_setup_$symbol",o.toString()).apply()
    }
    private fun expectancyKey(symbol:String,side:String)="shadow_closed_net_r_"+symbol+"_"+side
    private fun loadExpectancyStats(symbol:String):Map<String,List<Double>> = listOf("LONG","SHORT").associateWith { side ->
        prefs.getString(expectancyKey(symbol,side),"").orEmpty().split(",").mapNotNull{it.toDoubleOrNull()}.takeLast(200)
    }
    private fun saveShadow(symbol:String,r:StrategyResult,barTime:Long){
        val o=JSONObject().put("side",r.signal).put("entry",r.entry)
            .put("openedBar",barTime).put("lastBar",barTime)
        prefs.edit().putString("shadow_setup_$symbol",o.toString()).apply()
    }
    private fun closeShadowOnReversal(symbol:String,newSignal:String,exit:Double){
        val raw=prefs.getString("shadow_setup_$symbol",null)?:return
        val o=try{JSONObject(raw)}catch(_:Exception){prefs.edit().remove("shadow_setup_$symbol").apply();return}
        val oldSide=o.optString("side")
        if(!isDirectionalSignal(newSignal)||newSignal==oldSide)return
        val entry=o.optDouble("entry",0.0)
        if(entry>0.0&&exit>0.0){
            val direction=if(oldSide=="LONG")1.0 else -1.0
            val net=direction*(exit-entry)/entry-0.001
            val key=expectancyKey(symbol,oldSide)
            val prior=prefs.getString(key,"").orEmpty().split(",").mapNotNull{it.toDoubleOrNull()}.takeLast(199)
            prefs.edit().putString(key,(prior+net).joinToString(",")).remove("shadow_setup_$symbol").apply()
        }else prefs.edit().remove("shadow_setup_$symbol").apply()
    }
    private fun manageShadow(symbol:String,bar:MarketCandle){
        val raw=prefs.getString("shadow_setup_$symbol",null)?:return
        val o=try{JSONObject(raw)}catch(_:Exception){prefs.edit().remove("shadow_setup_$symbol").apply();return}
        if(bar.time<=o.optLong("lastBar",o.optLong("openedBar",0L)))return
        o.put("lastBar",bar.time)
        prefs.edit().putString("shadow_setup_$symbol",o.toString()).apply()
    }
    private fun recordVirtualClose(symbol:String,exit:Double){
        val raw=prefs.getString("open_setup_$symbol",null)?:return
        val o=try{JSONObject(raw)}catch(_:Exception){return}
        val side=o.optString("side")
        val entry=o.optDouble("entry",0.0)
        if((side!="LONG"&&side!="SHORT")||entry<=0.0||exit<=0.0)return
        val direction=if(side=="LONG")1.0 else -1.0
        val net=direction*(exit-entry)/entry-0.001
        val key="closed_net_return_"+symbol+"_"+side
        val prior=prefs.getString(key,"").orEmpty().split(",").mapNotNull{it.toDoubleOrNull()}.takeLast(199)
        prefs.edit().putString(key,(prior+net).joinToString(",")).apply()
    }
    // Positions persist through all price movements and are closed only by an opposite confirmed signal.
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
