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
                // Every 60 seconds: use Toobit's live futures ticker for real-time stop/target checks.
                val price=ExchangeMarketData.lastPrice(exchange,symbol)
                manageOpen(symbol,price)

                // Strategy runs only when a newer fully closed 5m candle is available.
                val five=ExchangeMarketData.candles(exchange,symbol,"5m")
                val latestClosedCandleTime=five.last().time
                val key="last_analysis_candle_$symbol"
                val previousCandleTime=prefs.getLong(key,-1L)
                if(previousCandleTime<0L){
                    // First launch establishes a baseline instead of issuing a delayed historical signal.
                    prefs.edit().putLong(key,latestClosedCandleTime).apply()
                } else if(latestClosedCandleTime>previousCandleTime){
                    // All three datasets contain closed candles only; 15m/30m may be unchanged between scans.
                    val fifteen=ExchangeMarketData.candles(exchange,symbol,"15m")
                    val thirty=ExchangeMarketData.candles(exchange,symbol,"30m")
                    val r=TradingStrategy.evaluate(five,fifteen,thirty)
                    val open=prefs.getString("open_setup_$symbol",null)
                    val previous=prefs.getString("last_signal_$symbol",null)
                    if(open==null && isDirectionalSignal(r.signal) && previous!=r.signal) {
                        saveOpen(symbol,r)
                        notifySignal(symbol,r)
                    } else if(open!=null && isDirectionalSignal(r.signal)){
                        val old=JSONObject(open).optString("side")
                        if(old!=r.signal){clearOpen(symbol);saveOpen(symbol,r);notifySignal(symbol,r)}
                    }
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

    private fun saveOpen(symbol:String,r:StrategyResult){
        val o=JSONObject().put("side",r.signal).put("entry",r.entry).put("stop",r.stop)
            .put("tp1",r.target1).put("tp2",r.target2).put("tp3",r.target3)
            .put("tp1Done",false).put("tp2Done",false)
        prefs.edit().putString("open_setup_$symbol",o.toString()).apply()
    }
    private fun manageOpen(symbol:String,price:Double){
        val raw=prefs.getString("open_setup_$symbol",null)?:return
        val s=try{JSONObject(raw)}catch(_:Exception){prefs.edit().remove("open_setup_$symbol").apply();return}
        val long=s.optString("side")=="LONG"
        // Migrate setups saved by older app versions, which tracked only TP1/TP2.
        if(!s.has("tp3"))s.put("tp3",s.optDouble("tp2"))
        if(!s.has("tp2Done"))s.put("tp2Done",false)
        val stop=if(long)price<=s.optDouble("stop") else price>=s.optDouble("stop")
        if(stop){closeOpen(symbol,"حد ضرر فعال شد • قیمت ${fmt(price)}");return}
        val tp1=if(long)price>=s.optDouble("tp1") else price<=s.optDouble("tp1")
        if(tp1&&!s.optBoolean("tp1Done")){
            s.put("tp1Done",true);prefs.edit().putString("open_setup_$symbol",s.toString()).apply()
            notifyExit(symbol,"مرحله اول حد سود لمس شد",
                "قیمت فعلی: ${fmt(price)} • هدف دوم: ${fmt(s.optDouble("tp2"))} • یادآوری خروج پله‌ای؛ سفارش خودکار اجرا نمی‌شود")
        }
        val tp2=if(long)price>=s.optDouble("tp2") else price<=s.optDouble("tp2")
        if(tp2&&!s.optBoolean("tp2Done")){
            s.put("tp2Done",true);prefs.edit().putString("open_setup_$symbol",s.toString()).apply()
            notifyExit(symbol,"مرحله دوم حد سود لمس شد",
                "قیمت فعلی: ${fmt(price)} • هدف سوم: ${fmt(s.optDouble("tp3"))} • یادآوری خروج پله‌ای؛ سفارش خودکار اجرا نمی‌شود")
        }
        val tp3=if(long)price>=s.optDouble("tp3") else price<=s.optDouble("tp3")
        if(tp3)closeOpen(symbol,"حد سود سوم فعال شد • قیمت ${fmt(price)}")
    }
    private fun clearOpen(symbol:String){prefs.edit().remove("open_setup_$symbol").apply()}
    private fun closeOpen(symbol:String,reason:String){clearOpen(symbol);notifyExit(symbol,"خروج از موقعیت • $symbol",reason)}
    private fun fmt(v:Double)=if(v>=100)"%.2f".format(v) else if(v>=1)"%.4f".format(v) else "%.7f".format(v)
    private fun notifySignal(symbol:String,r:StrategyResult){
        val body=buildString{
            append("جهت: ${if(r.signal=="LONG")"لانگ" else "شورت"}\nورود: ${fmt(r.entry)}\nحد ضرر: ${fmt(r.stop)}\nحد سود ۱: ${fmt(r.target1)}\nحد سود ۲: ${fmt(r.target2)}\nحد سود ۳: ${fmt(r.target3)}\nامتیاز تأیید: ${r.score}\n")
            append("مدیریت خروج در این نسخه هشدار مرحله‌ای است و سفارش صرافی را خودکار اجرا نمی‌کند.\n")
            append("دلایل: ${(r.reasons+r.smc).joinToString("، ")}")
        }
        val b=notificationBuilder(alertChannel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("سیگنال ${r.signal} • $symbol").setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
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
