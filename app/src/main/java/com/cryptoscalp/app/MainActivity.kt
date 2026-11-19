package com.cryptoscalp.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Color
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var coinChoices: LinearLayout
    private lateinit var selectedChoices: LinearLayout
    private lateinit var coinSearch: EditText
    private val symbols = mutableListOf("BTC-SWAP-USDT", "ETH-SWAP-USDT", "BNB-SWAP-USDT", "SOL-SWAP-USDT", "XRP-SWAP-USDT", "DOGE-SWAP-USDT", "ADA-SWAP-USDT", "TRX-SWAP-USDT", "LINK-SWAP-USDT", "AVAX-SWAP-USDT")
    private val selectedSymbols = linkedSetOf<String>().apply { addAll(symbols) }
    private val checkboxes = mutableMapOf<String, CheckBox>()
    private val lastSignals = mutableMapOf<String, String>()
    private var busy = false
    private val channelId = "scalp_signals"
    private val handler = Handler(Looper.getMainLooper())
    private val periodicRefresh = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 30_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
        }
        loadContracts()
        refresh()
        handler.postDelayed(periodicRefresh, 30_000)
    }

    override fun onDestroy() { handler.removeCallbacks(periodicRefresh); super.onDestroy() }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(12, 18, 30))
        }
        val title = text("CRYPTO SCALP", 25, Color.WHITE, true)
        root.addView(title)
        root.addView(text("Toobit USDT Perpetual • سیگنال لانگ و شورت", 14, Color.LTGRAY))
        root.addView(text("ارزهای انتخاب‌شده:", 15, Color.WHITE, true))
        selectedChoices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(selectedChoices)
        root.addView(text("ارزهای موردنظر را جستجو و انتخاب کن:", 15, Color.WHITE, true))
        coinSearch = EditText(this).apply {
            hint = "جستجوی نماد ارز؛ مثلاً BTC یا SOL"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        root.addView(coinSearch)
        coinSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderCoinChoices(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        coinChoices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(coinChoices)
        val refresh = Button(this).apply { text = "تحلیل ارزهای انتخاب‌شده" }
        root.addView(refresh)
        status = text("در حال اتصال به بازار فیوچرز Toobit…", 14, Color.YELLOW)
        root.addView(status)
        result = text("منتظر دریافت داده بازار…", 17, Color.WHITE)
        result.setPadding(0, 24, 0, 0)
        root.addView(result)
        val note = text("فقط تحلیل و هشدار؛ هیچ سفارشی ثبت نمی‌شود. قیمت‌ها و سطوح تخمینی‌اند.", 12, Color.LTGRAY)
        note.setPadding(0, 28, 0, 0)
        root.addView(note)
        setContentView(ScrollView(this).apply { addView(root) })
        renderSelectedChoices()
        refresh.setOnClickListener { refresh() }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 10)
        }

    private fun loadContracts() {
        thread {
            try {
                val json = getJson("/api/v1/exchangeInfo")
                val arr = json.optJSONArray("contracts") ?: JSONArray()
                val active = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    if (item.optString("status") == "TRADING") {
                        val s = item.optString("symbol")
                        if (s.endsWith("-SWAP-USDT") && (item.optString("quoteAsset").isBlank() || item.optString("quoteAsset") == "USDT")) active.add(s)
                    }
                }
                val preferredBases = listOf("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "LINK", "AVAX")
                val preferred = preferredBases.map { "$it-SWAP-USDT" }.filter { active.contains(it) }
                val ordered = preferred + active.filterNot { preferred.contains(it) }.sorted()
                runOnUiThread {
                    symbols.clear()
                    symbols.addAll(ordered)
                    selectedSymbols.retainAll(ordered.toSet())
                    if (selectedSymbols.isEmpty()) selectedSymbols.addAll(preferred)
                    renderCoinChoices(coinSearch.text.toString())
                    renderSelectedChoices()
                    status.text = "اتصال برقرار • ${ordered.size} قرارداد فعال USDT فیوچرز؛ ۱۰ ارز اصلی انتخاب‌شده"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطای دریافت فهرست قراردادها: ${e.message}" }
            }
        }
    }

    private fun renderCoinChoices(query: String) {
        if (!::coinChoices.isInitialized) return
        val q = query.trim().uppercase()
        coinChoices.removeAllViews()
        val matches = symbols.filter { symbol ->
            val base = symbol.removeSuffix("-SWAP-USDT")
            q.isEmpty() || base.contains(q) || symbol.contains(q)
        }
        for (symbol in matches) {
            val base = symbol.removeSuffix("-SWAP-USDT")
            val box = checkboxes.getOrPut(symbol) {
                CheckBox(this).apply {
                    text = base
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedSymbols.add(symbol) else selectedSymbols.remove(symbol)
                        renderSelectedChoices()
                    }
                }
            }
            box.setOnCheckedChangeListener(null)
            box.isChecked = selectedSymbols.contains(symbol)
            box.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedSymbols.add(symbol) else selectedSymbols.remove(symbol)
                renderSelectedChoices()
            }
            coinChoices.addView(box)
        }
        if (matches.isEmpty()) coinChoices.addView(text("ارزی با این نماد پیدا نشد.", 14, Color.LTGRAY))
    }

    private fun renderSelectedChoices() {
        if (!::selectedChoices.isInitialized) return
        selectedChoices.removeAllViews()
        val chosen = selectedSymbols.toList()
        if (chosen.isEmpty()) {
            selectedChoices.addView(text("هنوز ارزی انتخاب نشده است.", 14, Color.LTGRAY))
            return
        }
        val names = chosen.map { it.removeSuffix("-SWAP-USDT") }
        selectedChoices.addView(text(names.joinToString("  •  "), 15, Color.rgb(80, 220, 150), true))
        selectedChoices.addView(text("تعداد انتخاب‌شده: ${chosen.size}", 12, Color.LTGRAY))
    }

    private fun refresh() {
        if (busy) return
        busy = true
        val chosen = selectedSymbols.toList()
        if (chosen.isEmpty()) {
            status.text = "حداقل یک ارز را تیک بزن."
            return
        }
        status.text = "در حال تحلیل ${chosen.size} ارز انتخاب‌شده …"
        thread {
            val reports = mutableListOf<String>()
            var completed = 0
            for (symbol in chosen) {
                try {
                    val one = candles(symbol, "1m")
                    val five = candles(symbol, "5m")
                    val fifteen = candles(symbol, "15m")
                    val hour = candles(symbol, "1h")
                    val signal = analyze(one, five, fifteen, hour)
                    reports.add("【$symbol】\\n${signal.second}")
                    val previous = lastSignals[symbol]
                    if (signal.first != "WAIT" && signal.first != previous) notifySignal(symbol, signal.first, signal.second)
                    lastSignals[symbol] = signal.first
                    completed++
                } catch (e: Exception) {
                    reports.add("【$symbol】\\nخطا در تحلیل: ${e.message}")
                }
            }
            runOnUiThread {
                status.text = "پایان تحلیل: $completed از ${chosen.size} ارز"
                result.text = reports.joinToString("\\n\\n--------------------\\n\\n")
                busy = false
            }
        }
    }

    private fun candles(symbol: String, interval: String): List<Double> {
        val now = System.currentTimeMillis()
        val duration = when (interval) { "1m" -> 60000L; "5m" -> 300000L; "15m" -> 900000L; else -> 3600000L }
        val url = "/quote/v1/markPrice/klines?symbol=$symbol&interval=$interval&from=${now-duration*160}&to=$now&limit=160"
        val json = getJson(url)
        val data = json.optJSONArray("data") ?: JSONArray()
        val closes = mutableListOf<Double>()
        for (i in 0 until data.length()) {
            val row = data.getJSONObject(i)
            val c = row.optString("close").toDoubleOrNull()
            if (c != null && c > 0) closes.add(c)
        }
        if (closes.size < 60) throw IllegalStateException("کندل کافی برای $interval دریافت نشد")
        return closes
    }

    private fun getJson(path: String): JSONObject {
        val conn = (URL("https://api.toobit.com$path").openConnection() as HttpURLConnection)
        conn.connectTimeout = 12000; conn.readTimeout = 15000; conn.requestMethod = "GET"
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally { conn.disconnect() }
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val k = 2.0 / (period + 1)
        var value = values.take(period).average()
        for (v in values.drop(period)) value = v * k + value * (1-k)
        return value
    }

    private fun rsi(values: List<Double>): Double {
        val changes = values.takeLast(15).zipWithNext { a, b -> b-a }
        val gain = changes.filter { it > 0 }.sum() / 14.0
        val loss = -changes.filter { it < 0 }.sum() / 14.0
        if (loss == 0.0) return 100.0
        return 100 - 100/(1 + gain/loss)
    }

    private fun atrProxy(values: List<Double>): Double {
        val diffs = values.takeLast(15).zipWithNext { a, b -> abs(b-a) }
        return diffs.average() * 1.8
    }

    private fun analyze(one: List<Double>, five: List<Double>, fifteen: List<Double>, hour: List<Double>): Pair<String, String> {
        val price = one.last()
        val up1h = ema(hour,9) > ema(hour,21) && ema(hour,21) > ema(hour,50)
        val dn1h = ema(hour,9) < ema(hour,21) && ema(hour,21) < ema(hour,50)
        val up15 = ema(fifteen,9) > ema(fifteen,21) && ema(fifteen,21) > ema(fifteen,50)
        val dn15 = ema(fifteen,9) < ema(fifteen,21) && ema(fifteen,21) < ema(fifteen,50)
        val up5 = ema(five,9) > ema(five,21) && rsi(five) > 52
        val dn5 = ema(five,9) < ema(five,21) && rsi(five) < 48
        val signal = when { up1h && up15 && up5 -> "LONG"; dn1h && dn15 && dn5 -> "SHORT"; else -> "WAIT" }
        val atr = max(atrProxy(five), price*0.0005)
        val stop = if (signal == "LONG") price-1.5*atr else price+1.5*atr
        val tp1 = if (signal == "LONG") price+1.5*atr else price-1.5*atr
        val tp2 = if (signal == "LONG") price+2.5*atr else price-2.5*atr
        val direction = if (signal == "LONG") "لانگ" else if (signal == "SHORT") "شورت" else "بدون ورود"
        val details = "وضعیت: $direction\n\nقیمت مرجع ورود: ${fmt(price)}\nحد ضرر: ${fmt(stop)}\nحد سود ۱: ${fmt(tp1)}\nحد سود ۲: ${fmt(tp2)}\n\nتأیید روند ۱ساعته: ${if(up1h) "صعودی" else if(dn1h) "نزولی" else "خنثی"}\nتأیید روند ۱۵دقیقه: ${if(up15) "صعودی" else if(dn15) "نزولی" else "خنثی"}\nتأیید ورود ۵دقیقه: ${if(up5) "لانگ" else if(dn5) "شورت" else "تأیید نشده"}\nRSI پنج‌دقیقه: ${"%.1f".format(rsi(five))}\n\nخروج: با برخورد به حد سود/ضرر یا صدور سیگنال معکوس؛ قیمت‌ها تضمینی نیستند."
        return Pair(signal, details)
    }

    private fun fmt(v: Double) = if (v >= 100) "%.2f".format(v) else if (v >= 1) "%.4f".format(v) else "%.7f".format(v)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(channelId, "هشدار سیگنال‌ها", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notifySignal(symbol: String, signal: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, channelId) else android.app.Notification.Builder(this)
        n.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("$symbol • $signal").setContentText(body.lines().take(3).joinToString(" | ")).setStyle(android.app.Notification.BigTextStyle().bigText(body))
        manager.notify(symbol.hashCode(), n.build())
    }
}
