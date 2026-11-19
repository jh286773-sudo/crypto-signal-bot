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
    private val defaultSymbols = listOf("BTC-SWAP-USDT","ETH-SWAP-USDT","BNB-SWAP-USDT","SOL-SWAP-USDT","XRP-SWAP-USDT","DOGE-SWAP-USDT","ADA-SWAP-USDT","TRX-SWAP-USDT","LINK-SWAP-USDT","AVAX-SWAP-USDT")
    private val lastSignals = mutableMapOf<String,String>()
    private val tradePrefs by lazy { getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE) }
    private data class OpenSetup(val side:String, val entry:Double, val stop:Double, val tp1:Double, val tp2:Double, val tp1Done:Boolean)
    @Volatile private var scanning = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val loop = object : Runnable { override fun run() { scan(); handler.postDelayed(this, 30_000L) } }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val foregroundNotification = serviceNotification("پایش بازار فعال است")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(9100, foregroundNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(9100, foregroundNotification)
        }
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
            val activeSymbols = selectedSymbols()
            for (s in activeSymbols) try {
                val price = markPrice(s)
                manageOpenSetup(s, price)
                val one=closes(s,"1m"); val five=closes(s,"5m"); val fifteen=closes(s,"15m"); val hour=closes(s,"1h")
                val r=analyze(one,five,fifteen,hour); count++
                val open = loadOpenSetup(s)
                val previousSignal = tradePrefs.getString("last_signal_" + s, null)
                if (open == null && r.first != "WAIT" && previousSignal != r.first) {
                    val setup = makeSetup(r.first, one.last(), five)
                    saveOpenSetup(s, setup)
                    notifySignal(s, r.first, r.second)
                } else if (open != null && r.first != "WAIT" && r.first != open.side) {
                    closeOpenSetup(s, "سیگنال معکوس صادر شد؛ موقعیت قبلی بسته شد.")
                    val setup = makeSetup(r.first, one.last(), five)
                    saveOpenSetup(s, setup)
                    notifySignal(s, r.first, r.second)
                }
                lastSignals[s]=r.first
                tradePrefs.edit().putString("last_signal_" + s, r.first).apply()
            } catch (_: Exception) { /* retry on next cycle */ }
            scanning=false
            handler.post { updateStatus("پایش فعال • تحلیل موفق $count از ${activeSymbols.size} قرارداد • چرخه بعدی ۳۰ ثانیه") }
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

    private fun selectedSymbols(): List<String> {
        val saved = tradePrefs.getStringSet("selected_symbols", null)?.toList().orEmpty()
        val openSymbols = tradePrefs.all.keys.filter { it.startsWith("open_setup_") }.map { it.removePrefix("open_setup_") }
        return ((if (saved.isEmpty()) defaultSymbols else saved) + openSymbols).distinct().take(100)
    }

    private fun markPrice(symbol: String): Double {
        val conn = URL("https://api.toobit.com/quote/v1/markPrice?symbol=" + symbol).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 12000
        try {
            val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return obj.optString("price").toDoubleOrNull()?.takeIf { it > 0 } ?: throw IllegalStateException("قیمت لحظه‌ای نامعتبر")
        } finally { conn.disconnect() }
    }

    private fun makeSetup(side: String, entry: Double, five: List<Double>): OpenSetup {
        val changes = five.takeLast(15).zipWithNext { a, b -> abs(b - a) }
        val range = max((changes.average().takeIf { it.isFinite() } ?: 0.0) * 1.8, entry * 0.0005)
        val stop = if (side == "LONG") entry - 1.5 * range else entry + 1.5 * range
        val tp1 = if (side == "LONG") entry + 1.5 * range else entry - 1.5 * range
        val tp2 = if (side == "LONG") entry + 2.5 * range else entry - 2.5 * range
        return OpenSetup(side, entry, stop, tp1, tp2, false)
    }

    private fun setupKey(symbol: String) = "open_setup_" + symbol
    private fun loadOpenSetup(symbol: String): OpenSetup? {
        val raw = tradePrefs.getString(setupKey(symbol), null) ?: return null
        return try {
            val o = JSONObject(raw)
            OpenSetup(o.getString("side"), o.getDouble("entry"), o.getDouble("stop"), o.getDouble("tp1"), o.getDouble("tp2"), o.optBoolean("tp1Done"))
        } catch (_: Exception) { null }
    }
    private fun saveOpenSetup(symbol: String, s: OpenSetup) {
        val o = JSONObject().put("side", s.side).put("entry", s.entry).put("stop", s.stop).put("tp1", s.tp1).put("tp2", s.tp2).put("tp1Done", s.tp1Done)
        tradePrefs.edit().putString(setupKey(symbol), o.toString()).apply()
    }
    private fun clearOpenSetup(symbol: String) { tradePrefs.edit().remove(setupKey(symbol)).apply() }

    private fun manageOpenSetup(symbol: String, price: Double) {
        val s = loadOpenSetup(symbol) ?: return
        val stopHit = if (s.side == "LONG") price <= s.stop else price >= s.stop
        if (stopHit) { closeOpenSetup(symbol, "حد ضرر فعال شد. قیمت فعلی: " + fmt(price)); return }
        val tp1Hit = if (s.side == "LONG") price >= s.tp1 else price <= s.tp1
        if (tp1Hit && !s.tp1Done) {
            saveOpenSetup(symbol, s.copy(tp1Done = true))
            notifyExit(symbol, "حد سود اول فعال شد", "قیمت فعلی: " + fmt(price) + "\\nحد سود دوم: " + fmt(s.tp2) + "\\nموقعیت هنوز باز است.")
        }
        val tp2Hit = if (s.side == "LONG") price >= s.tp2 else price <= s.tp2
        if (tp2Hit) closeOpenSetup(symbol, "حد سود دوم فعال شد. قیمت فعلی: " + fmt(price))
    }
    private fun closeOpenSetup(symbol: String, reason: String) {
        clearOpenSetup(symbol)
        notifyExit(symbol, "خروج از معامله • " + symbol, reason)
    }
    private fun notifyExit(symbol: String, title: String, body: String) {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, alertChannel) else Notification.Builder(this)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(symbol.hashCode() xor title.hashCode(),
            b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).build())
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