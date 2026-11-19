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
import kotlin.math.abs
import kotlin.math.max

class CryptoMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceChannel = "monitor_service"
    private val alertChannel = "scalp_signals"
    private val symbols = listOf("BTC-SWAP-USDT","ETH-SWAP-USDT","BNB-SWAP-USDT","SOL-SWAP-USDT","XRP-SWAP-USDT","DOGE-SWAP-USDT","ADA-SWAP-USDT","TRX-SWAP-USDT","LINK-SWAP-USDT","AVAX-SWAP-USDT")
    private val lastSignals = mutableMapOf<String,String>()
    @Volatile private var scanning = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val loop = object : Runnable { override fun run() { scan(); handler.postDelayed(this, 30_000L) } }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(9100, serviceNotification("پایش بازار فعال است"))
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { handler.post { updateStatus("اینترنت وصل شد؛ تحلیل از سر گرفته می‌شود"); scan() } }
            override fun onLost(network: Network) { handler.post { updateStatus("اینترنت قطع است؛ تلاش مجدد خودکار") } }
        }
        callback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(loop)
        scan()
        handler.postDelayed(loop, 30_000L)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        callback?.let { try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun online(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun scan() {
        if (scanning) return
        if (!online()) { updateStatus("اینترنت قطع است؛ منتظر اتصال مجدد"); return }
        scanning = true
        updateStatus("در حال تحلیل قراردادهای فیوچرز؛ چرخه ۳۰ ثانیه‌ای")
        thread(name="market-monitor") {
            var count=0
            for (s in symbols) try {
                val one=closes(s,"1m"); val five=closes(s,"5m"); val fifteen=closes(s,"15m"); val hour=closes(s,"1h")
                val r=analyze(one,five,fifteen,hour); count++
                if (r.first!="WAIT" && lastSignals[s]!=r.first) notifySignal(s,r.first,r.second)
                lastSignals[s]=r.first
            } catch (_: Exception) { /* retry on next cycle */ }
            scanning=false
            handler.post { updateStatus("پایش فعال • تحلیل موفق $count از ${symbols.size} قرارداد • چرخه بعدی ۳۰ ثانیه") }
        }
    }

    private fun closes(symbol:String, interval:String):List<Double> {
        val now=System.currentTimeMillis()
        val span=when(interval){"1m"->60_000L;"5m"->300_000L;"15m"->900_000L;else->3_600_000L}
        val path="/quote/v1/markPrice/klines?symbol=$symbol&interval=$interval&from=${now-span*160}&to=$now&limit=160"
        val c=URL("https://api.toobit.com$path").openConnection() as HttpURLConnection
        c.connectTimeout=12_000;c.readTimeout=15_000
        try {
            val data=JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optJSONArray("data")?:JSONArray()
            val out=mutableListOf<Double>()
            for(i in 0 until data.length()) { val v=data.getJSONObject(i).optString("close").toDoubleOrNull(); if(v!=null&&v>0)out.add(v) }
            if(out.size<60) throw IllegalStateException("کندل کافی نیست")
            return out
        } finally { c.disconnect() }
    }

    private fun ema(v:List<Double>,p:Int):Double {
        val k=2.0/(p+1);var x=v.take(p).average()
        for(a in v.drop(p))x=a*k+x*(1-k)
        return x
    }
    private fun rsi(v:List<Double>):Double {
        val d=v.takeLast(15).zipWithNext{a,b->b-a}
        val gain=d.filter{it>0}.sum()/14.0;val loss=-d.filter{it<0}.sum()/14.0
        if(loss==0.0)return 100.0
        return 100-100/(1+gain/loss)
    }
    private fun fmt(v:Double)=if(v>=100)"%.2f".format(v) else if(v>=1)"%.4f".format(v) else "%.7f".format(v)
    private fun analyze(one:List<Double>,five:List<Double>,fifteen:List<Double>,hour:List<Double>):Pair<String,String> {
        val p=one.last()
        val u1=ema(hour,9)>ema(hour,21)&&ema(hour,21)>ema(hour,50)
        val d1=ema(hour,9)<ema(hour,21)&&ema(hour,21)<ema(hour,50)
        val u15=ema(fifteen,9)>ema(fifteen,21)&&ema(fifteen,21)>ema(fifteen,50)
        val d15=ema(fifteen,9)<ema(fifteen,21)&&ema(fifteen,21)<ema(fifteen,50)
        val u5=ema(five,9)>ema(five,21)&&rsi(five)>52
        val d5=ema(five,9)<ema(five,21)&&rsi(five)<48
        val sig=when{u1&&u15&&u5->"LONG";d1&&d15&&d5->"SHORT";else->"WAIT"}
        val atr=max(five.takeLast(15).zipWithNext{a,b->abs(b-a)}.average()*1.8,p*0.0005)
        val stop=if(sig=="LONG")p-1.5*atr else p+1.5*atr
        val t1=if(sig=="LONG")p+1.5*atr else p-1.5*atr
        val t2=if(sig=="LONG")p+2.5*atr else p-2.5*atr
        val dir=if(sig=="LONG")"لانگ" else if(sig=="SHORT")"شورت" else "بدون ورود"
        return Pair(sig,"وضعیت: $dir\nورود مرجع: ${fmt(p)}\nحد ضرر: ${fmt(stop)}\nحد سود ۱: ${fmt(t1)}\nحد سود ۲: ${fmt(t2)}\nRSI پنج‌دقیقه: ${"%.1f".format(rsi(five))}\nتأیید روند: ۱ساعت، ۱۵دقیقه و ۵دقیقه")
    }

    private fun createChannels() {
        if(Build.VERSION.SDK_INT>=26) {
            val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(serviceChannel,"وضعیت پایش ربات",NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(alertChannel,"هشدار سیگنال‌ها",NotificationManager.IMPORTANCE_HIGH))
        }
    }
    private fun serviceNotification(msg:String):Notification {
        val b=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,serviceChannel) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Crypto Scalp • پایش فعال").setContentText(msg).setOngoing(true).build()
    }
    private fun updateStatus(msg:String) { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9100,serviceNotification(msg)) }
    private fun notifySignal(symbol:String,signal:String,body:String) {
        val b=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,alertChannel) else Notification.Builder(this)
        val title=if(signal=="LONG")"سیگنال لانگ" else "سیگنال شورت"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(symbol.hashCode(),
            b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("$title • $symbol").setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).build())
    }
    companion object {
        const val ACTION_START="com.cryptoscalp.app.START_MONITOR"
        const val ACTION_STOP="com.cryptoscalp.app.STOP_MONITOR"
    }
}