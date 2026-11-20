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
            var lastDiagnostic=""
            val now=System.currentTimeMillis()
            for(symbol in list)try{
                // Keep a tracked position open until an opposite confirmed signal arrives.
                // No price-based SL/TP exit is used in signal-reversal hold mode.

                // Only the user's selected timeframes are fetched and analyzed.
                val marketAsOfMs=ExchangeMarketData.exchangeTimeMs()
                val savedTimeframes=prefs.getStringSet("selected_timeframes",setOf("5m","15m","30m")).orEmpty()
                val selectedTimeframes=ExchangeMarketData.supportedTimeframes.filter { it in savedTimeframes }
                if(selectedTimeframes.isEmpty()) throw IllegalStateException("هیچ تایم‌فریمی انتخاب نشده است.")
                val slowestDuration=selectedTimeframes.maxOf { ExchangeMarketData.durationMs(it) }
                val candleMap=selectedTimeframes.associateWith { tf ->
                    val duration=ExchangeMarketData.durationMs(tf)
                    val historyLimit=(slowestDuration/duration+220L).coerceAtLeast(260L).coerceAtMost(1000L).toInt()
                    ExchangeMarketData.candles(exchange,symbol,tf,marketAsOfMs,historyLimit)
                }
                val latestClosedCandleTime=selectedTimeframes.minOf { tf ->
                    candleMap.getValue(tf).last().time + ExchangeMarketData.durationMs(tf)
                }
                // Keep a separate candle baseline for each selected timeframe set.
                // This prevents a previous faster selection from delaying a slower one.
                val selectionKey=selectedTimeframes.joinToString("_")
                val key="last_analysis_candle_${symbol}_$selectionKey"
                val previousCandleTime=prefs.getLong(key,-1L)
                if(previousCandleTime<0L){
                    // Establish a baseline without sending a delayed historical alert,
                    // but still analyze once so the user immediately sees per-timeframe diagnostics.
                    val baseline=TradingStrategy.evaluate(candleMap,marketAsOfMs)
                    recordSuccessfulAnalysis()
                    analyzed++
                    val candleCounts=selectedTimeframes.joinToString("، ") { tf -> "$tf=${candleMap.getValue(tf).size}" }
                    lastDiagnostic=symbol+" • تحلیل اولیه انجام شد (بدون اعلان تاریخی) • کندل‌ها: "+candleCounts+" • "+baseline.reasons.joinToString(" ؛ ")
                    prefs.edit().putLong(key,latestClosedCandleTime).apply()
                } else if(latestClosedCandleTime>previousCandleTime){
                    val r=TradingStrategy.evaluate(candleMap,marketAsOfMs)
                    recordSuccessfulAnalysis()
                    lastDiagnostic=symbol+" • تحلیل کندل جدید • تایم‌فریم‌ها: "+selectedTimeframes.joinToString("، ")+" • "+r.reasons.joinToString(" ؛ ")
                    closeShadowOnReversal(symbol,r.signal,r.entry)
                    if(isDirectionalSignal(r.signal) && prefs.getString("shadow_setup_$symbol",null)==null) saveShadow(symbol,r,latestClosedCandleTime)
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
                } else {
                    lastDiagnostic=symbol+" • داده‌های تایم‌فریم‌های "+selectedTimeframes.joinToString("، ")+" دریافت شد؛ منتظر بسته‌شدن کندل مشترک جدید هستیم. آخرین مرز مشترک: "+latestClosedCandleTime
                }
                ok++
            }catch(e:Exception){
                failed++
                val errorText="$symbol: ${e.message ?: e.javaClass.simpleName}"
                if(firstError.isBlank()) firstError=errorText
                lastDiagnostic="خطای دریافت/تحلیل $errorText"
            }
            scanning=false
            handler.post{
                val totalAnalyses=prefs.getInt("analysis_count_total",0)
                val lastAnalysisAt=prefs.getLong("last_successful_analysis_at",0L)
                val detail=if(failed>0) " • خطا $failed: $firstError" else ""
                val waiting=if(analyzed==0 && failed==0) " • هنوز کندل بسته‌شده مشترک جدید نرسیده" else ""
                val lastAt=if(lastAnalysisAt>0L) " • آخرین تحلیل: $lastAnalysisAt" else ""
                val statusLine="توبیت • پایش $ok از ${list.size} نماد • تحلیل موفق این چرخه: $analyzed • کل تحلیل‌ها: $totalAnalyses • پایش هر ۶۰ ثانیه$detail$waiting$lastAt"
                if(lastDiagnostic.isNotBlank()) prefs.edit().putString("latest_signal_diagnostic",lastDiagnostic).apply()
                updateStatus(statusLine+(if(lastDiagnostic.isNotBlank()) "\n"+lastDiagnostic else ""))
            }
        }
    }
    private fun recordSuccessfulAnalysis(){
        val total=prefs.getInt("analysis_count_total",0)+1
        prefs.edit().putInt("analysis_count_total",total)
            .putLong("last_successful_analysis_at",System.currentTimeMillis()).apply()
    }
    // WAIT is an internal no-trade state only; user-facing entry alerts are LONG or SHORT.
    private fun isDirectionalSignal(signal:String)=signal=="LONG"||signal=="SHORT"

    private fun saveOpen(symbol:String,r:StrategyResult,barTime:Long){
        val o=JSONObject().put("side",r.signal).put("entry",r.entry)
            .put("openedAt",System.currentTimeMillis()).put("openedBar",barTime)
        prefs.edit().putString("open_setup_$symbol",o.toString()).apply()
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
            // Shadow history is not used to gate signals; simply close the virtual setup.
            prefs.edit().remove("shadow_setup_$symbol").apply()
        }else prefs.edit().remove("shadow_setup_$symbol").apply()
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
            append("این اعلان راهنمای دستی است؛ برنامه سفارش صرافی را ثبت یا اجرا نمی‌کند.\\n")
            append("دلایل: ${r.reasons.joinToString("، ")}")
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
            append("دلایل: ${r.reasons.joinToString("، ")}")
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
    private fun updateStatus(message:String){
        prefs.edit().putString("latest_monitor_status",message)
            .putLong("latest_monitor_status_at",System.currentTimeMillis()).apply()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9100,serviceNotification(message))
    }
    companion object{
        const val ACTION_START="com.cryptoscalp.app.START_MONITOR"
        const val ACTION_STOP="com.cryptoscalp.app.STOP_MONITOR"
    }
}
