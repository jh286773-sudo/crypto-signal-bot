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
        scanning=true;updateStatus("در حال تحلیل ۱۰ ابزار حرفه‌ای و اسمارت‌مانی…")
        thread(name="market-monitor"){
            var ok=0;
            for(symbol in list)try{
                val one=candles(symbol,"1m");val five=candles(symbol,"5m");val fifteen=candles(symbol,"15m");val hour=candles(symbol,"1h")
                val price=markPrice(symbol)
                manageOpen(symbol,price)
                val r=TradingStrategy.evaluate(one,five,fifteen,hour)
                val open=prefs.getString("open_setup_$symbol",null)
                val previous=prefs.getString("last_signal_$symbol",null)
                if(open==null && r.signal!="WAIT" && previous!=r.signal) {
                    saveOpen(symbol,r)
                    notifySignal(symbol,r)
                } else if(open!=null && r.signal!="WAIT"){
                    val old=JSONObject(open).optString("side")
                    if(old!=r.signal){clearOpen(symbol);saveOpen(symbol,r);notifySignal(symbol,r)}
                }
                prefs.edit().putString("last_signal_$symbol",r.signal).apply();ok++
            }catch(_:Exception){}
            scanning=false
            handler.post{updateStatus("پایش فعال • تحلیل موفق $ok از ${list.size} نماد • هر ۶۰ ثانیه")}
        }
    }
    private fun candles(symbol:String,interval:String):List<MarketCandle>{
        val duration=when(interval){"1m"->60_000L;"5m"->300_000L;"15m"->900_000L;else->3_600_000L}
        val now=System.currentTimeMillis()
        val url="https://api.toobit.com/quote/v1/markPrice/klines?symbol=$symbol&interval=$interval&from=${now-duration*260}&to=$now&limit=260"
        val conn=URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout=12_000;conn.readTimeout=15_000
        try{
            val data=JSONObject(conn.inputStream.bufferedReader().use{it.readText()}).optJSONArray("data")?:JSONArray()
            val out=mutableListOf<MarketCandle>()
            for(i in 0 until data.length()){
                val o=data.getJSONObject(i)
                val open=o.optString("open").toDoubleOrNull()?:continue
                val high=o.optString("high").toDoubleOrNull()?:continue
                val low=o.optString("low").toDoubleOrNull()?:continue
                val close=o.optString("close").toDoubleOrNull()?:continue
                val volume=o.optString("volume").toDoubleOrNull()?:0.0
                if(open>0&&high>0&&low>0&&close>0)out.add(MarketCandle(open,high,low,close,volume))
            }
            if(out.size<60)throw IllegalStateException("کندل کافی نیست")
            return out
        }finally{conn.disconnect()}
    }
    private fun markPrice(symbol:String):Double{
        val c=URL("https://api.toobit.com/quote/v1/markPrice?symbol=$symbol").openConnection() as HttpURLConnection
        c.connectTimeout=10_000;c.readTimeout=12_000
        try{return JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optString("price").toDoubleOrNull()?.takeIf{it>0}?:throw IllegalStateException("قیمت نامعتبر")}
        finally{c.disconnect()}
    }
    private fun saveOpen(symbol:String,r:StrategyResult){
        val o=JSONObject().put("side",r.signal).put("entry",r.entry).put("stop",r.stop).put("tp1",r.target1).put("tp2",r.target2).put("tp1Done",false)
        prefs.edit().putString("open_setup_$symbol",o.toString()).apply()
    }
    private fun manageOpen(symbol:String,price:Double){
        val raw=prefs.getString("open_setup_$symbol",null)?:return
        val s=try{JSONObject(raw)}catch(_:Exception){prefs.edit().remove("open_setup_$symbol").apply();return}
        val long=s.optString("side")=="LONG"
        val stop=if(long)price<=s.optDouble("stop") else price>=s.optDouble("stop")
        if(stop){closeOpen(symbol,"حد ضرر فعال شد • قیمت ${fmt(price)}");return}
        val tp1=if(long)price>=s.optDouble("tp1") else price<=s.optDouble("tp1")
        if(tp1&&!s.optBoolean("tp1Done")){
            s.put("tp1Done",true);prefs.edit().putString("open_setup_$symbol",s.toString()).apply()
            notifyExit(symbol,"حد سود اول فعال شد","قیمت فعلی: ${fmt(price)} • حد سود دوم: ${fmt(s.optDouble("tp2"))}")
        }
        val tp2=if(long)price>=s.optDouble("tp2") else price<=s.optDouble("tp2")
        if(tp2)closeOpen(symbol,"حد سود دوم فعال شد • قیمت ${fmt(price)}")
    }
    private fun clearOpen(symbol:String){prefs.edit().remove("open_setup_$symbol").apply()}
    private fun closeOpen(symbol:String,reason:String){clearOpen(symbol);notifyExit(symbol,"خروج از موقعیت • $symbol",reason)}
    private fun fmt(v:Double)=if(v>=100)"%.2f".format(v) else if(v>=1)"%.4f".format(v) else "%.7f".format(v)
    private fun notifySignal(symbol:String,r:StrategyResult){
        val body=buildString{
            append("جهت: ${if(r.signal=="LONG")"لانگ" else "شورت"}\nورود: ${fmt(r.entry)}\nحد ضرر: ${fmt(r.stop)}\nحد سود ۱: ${fmt(r.target1)}\nحد سود ۲: ${fmt(r.target2)}\nامتیاز تأیید: ${r.score}\n")
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
