package com.cryptoscalp.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
    private lateinit var coinPickerButton: Button
    private var coinChoices: LinearLayout? = null
    private val symbols = mutableListOf<String>()
    private val selectedSymbols = linkedSetOf<String>()
    private val checkboxes = mutableMapOf<String, CheckBox>()
    private val lastSignals = mutableMapOf<String, String>()
    private var busy = false
    private val channelId = "scalp_signals"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        buildUi()
        val prefs = getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("manual_selection_initialized", false)) {
            prefs.edit().remove("selected_symbols").putBoolean("manual_selection_initialized", true).apply()
        }
        val saved = prefs.getStringSet("selected_symbols", null)
        if (saved != null) { selectedSymbols.clear(); selectedSymbols.addAll(saved) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
        }
        loadContracts()
        // Start the monitor automatically when the app is opened; its network callback resumes scans after reconnect.
        val monitorIntent = Intent(this, CryptoMonitorService::class.java).apply { action = CryptoMonitorService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(monitorIntent) else startService(monitorIntent)
    }


    private val gold = Color.rgb(212, 175, 55)
    private val goldLight = Color.rgb(245, 220, 135)
    private val ink = Color.rgb(8, 9, 12)
    private val panel = Color.rgb(22, 23, 27)
    private val muted = Color.rgb(166, 160, 145)

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 22, 20, 28)
            setBackgroundResource(R.drawable.app_background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 18)
        }
        val brandBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandBlock.addView(text("GOLD • CRYPTO", 12, gold, true))
        brandBlock.addView(text("AURUM X", 25, goldLight, true))
        brandBlock.addView(text("by JAVAD  •  تحلیل هوشمند بازار", 12, muted, true))
        header.addView(brandBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = Button(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(goldLight)
            background = rounded(panel, gold, 18)
            elevation = 8f
            minWidth = dp(52); minimumHeight = dp(48)
        }
        header.addView(settings)
        settings.setOnClickListener { showMonitorSettings(settings) }
        root.addView(header)

        val marketCard = card()
        marketCard.addView(text("وضعیت بازار", 13, gold, true))
        marketCard.addView(text("TOOBIT  /  USDT PERPETUAL", 11, muted, true))
        status = text("در حال اتصال به بازار فیوچرز Toobit…", 14, goldLight, true)
        marketCard.addView(status)
        root.addView(marketCard, marginParams(bottom = 14))

        val selectCard = card()
        selectCard.addView(text("دارایی‌های تحت نظر", 16, Color.WHITE, true))
        selectCard.addView(text("انتخاب ارز فقط به‌صورت دستی انجام می‌شود", 12, muted))
        coinPickerButton = Button(this).apply {
            textSize = 14f
            setTextColor(ink)
            background = rounded(gold, goldLight, 14)
            elevation = 6f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        selectCard.addView(coinPickerButton, marginParams(top = 10))
        renderSelectedChoices()
        coinPickerButton.setOnClickListener { showCoinPicker() }
        root.addView(selectCard, marginParams(bottom = 14))

        val actionCard = card()
        actionCard.addView(text("موتور تحلیل", 16, Color.WHITE, true))
        actionCard.addView(text("۱۰ ابزار تحلیلی • چند تایم‌فریم • اسمارت‌مانی", 12, muted))
        val refresh = Button(this).apply {
            text = "✦  شروع تحلیل بازار"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 27, 8))
            background = rounded(goldLight, gold, 20)
            elevation = 12f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        actionCard.addView(refresh, marginParams(top = 14))
        refresh.setOnClickListener { refresh() }
        root.addView(actionCard, marginParams(bottom = 14))

        val resultCard = card()
        resultCard.addView(text("سیگنال‌ها و نقاط معاملاتی", 16, goldLight, true))
        result = text("هنوز گزارشی ثبت نشده است. ارزهای موردنظر را انتخاب و تحلیل را شروع کن.", 14, Color.WHITE)
        result.setPadding(0, dp(12), 0, dp(4))
        resultCard.addView(result)
        root.addView(resultCard, marginParams(bottom = 14))

        val disclaimer = text("فقط تحلیل و هشدار؛ هیچ سفارشی ثبت نمی‌شود. سطوح قیمتی تخمینی‌اند و سود تضمین‌شده نیست.", 11, muted)
        disclaimer.gravity = Gravity.CENTER
        root.addView(disclaimer)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundResource(R.drawable.app_background)
            addView(root)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = rounded(panel, Color.rgb(105, 86, 39), 18)
        elevation = dp(5).toFloat()
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun showMonitorSettings(anchor: android.view.View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("شروع پایش در پس‌زمینه").setOnMenuItemClickListener {
            getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE).edit().putStringSet("selected_symbols", selectedSymbols.toSet()).apply()
            val i = Intent(this, CryptoMonitorService::class.java).apply { action = CryptoMonitorService.ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            status.text = "پایش پس‌زمینه فعال شد."
            true
        }
        menu.menu.add("بستن پایش در پس‌زمینه").setOnMenuItemClickListener {
            stopService(Intent(this, CryptoMonitorService::class.java))
            status.text = "پایش پس‌زمینه متوقف شد."
            true
        }
        menu.show()
    }

    private fun showCoinPicker() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 8); setBackgroundColor(Color.WHITE) }
        panel.addView(text("همه قراردادهای فعال USDT", 17, Color.BLACK, true))
        val search = EditText(this).apply { hint = "جستجوی نماد؛ مثلاً BTC یا SOL"; setSingleLine(true); setTextColor(Color.BLACK); setHintTextColor(Color.GRAY) }
        panel.addView(search)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55).toInt()) }
        panel.addView(scroll)
        coinChoices = list
        renderCoinChoices("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderCoinChoices(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        val dialog = AlertDialog.Builder(this).setTitle("انتخاب ارزها").setView(panel).setPositiveButton("بستن", null).setNegativeButton("پاک‌کردن انتخاب‌ها", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { selectedSymbols.clear(); getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE).edit().putStringSet("selected_symbols", emptySet()).apply(); renderCoinChoices(search.text.toString()); renderSelectedChoices() }
        }
        dialog.show()
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
                val ordered = active.distinct().sorted()
                runOnUiThread {
                    symbols.clear()
                    symbols.addAll(ordered)
                    selectedSymbols.retainAll(ordered.toSet())
                    getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE).edit().putStringSet("selected_symbols", selectedSymbols.toSet()).apply()
                    renderSelectedChoices()
                    status.text = if (selectedSymbols.isEmpty()) "فهرست قراردادها آماده است؛ برای شروع، ارزها را دستی انتخاب کن." else "اتصال برقرار • ${ordered.size} قرارداد فعال؛ ${selectedSymbols.size} ارز با انتخاب دستی"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطای دریافت فهرست قراردادها: ${e.message}" }
            }
        }
    }

    private fun renderCoinChoices(query: String) {
        val choices = coinChoices ?: return
        val q = query.trim().uppercase()
        choices.removeAllViews()
        val matches = symbols.filter { symbol -> val base = symbol.removeSuffix("-SWAP-USDT"); q.isEmpty() || base.contains(q) || symbol.contains(q) }
        for (symbol in matches) {
            val box = CheckBox(this).apply {
                text = symbol.removeSuffix("-SWAP-USDT"); textSize = 16f; setTextColor(Color.BLACK)
                isChecked = selectedSymbols.contains(symbol)
                setOnCheckedChangeListener { _, checked -> if (checked) selectedSymbols.add(symbol) else selectedSymbols.remove(symbol); getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE).edit().putStringSet("selected_symbols", selectedSymbols.toSet()).apply(); renderSelectedChoices() }
            }
            choices.addView(box)
        }
        if (matches.isEmpty()) choices.addView(text("ارزی با این نماد پیدا نشد.", 14, Color.DKGRAY))
    }

    private fun renderSelectedChoices() {
        if (!::coinPickerButton.isInitialized) return
        val count = selectedSymbols.size
        val names = selectedSymbols.map { it.removeSuffix("-SWAP-USDT") }
        coinPickerButton.text = if (count == 0) "انتخاب ارزها (هیچ ارزی انتخاب نشده)" else "انتخاب ارزها • $count انتخاب‌شده: " + names.take(5).joinToString("، ") + if (count > 5) " و ${count - 5} ارز دیگر" else ""
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
                    val one = marketCandles(symbol, "1m")
                    val five = marketCandles(symbol, "5m")
                    val fifteen = marketCandles(symbol, "15m")
                    val hour = marketCandles(symbol, "1h")
                    val signal = TradingStrategy.evaluate(one, five, fifteen, hour)
                    val detail = "جهت: ${signal.signal}\\nورود: ${fmt(signal.entry)}\\nحد ضرر: ${fmt(signal.stop)}\\nحد سود ۱: ${fmt(signal.target1)}\\nحد سود ۲: ${fmt(signal.target2)}\\nامتیاز: ${signal.score}\\nاندیکاتورها: ${signal.reasons.joinToString("، ")}\\nاسمارت‌مانی: ${signal.smc.joinToString("، ")}"
                    reports.add("【$symbol】\\n$detail")
                    val previous = lastSignals[symbol]
                    if (signal.signal != "WAIT" && signal.signal != previous) notifySignal(symbol, signal.signal, detail)
                    lastSignals[symbol] = signal.signal
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

    private fun marketCandles(symbol: String, interval: String): List<MarketCandle> {
        val now = System.currentTimeMillis()
        val duration = when (interval) { "1m" -> 60000L; "5m" -> 300000L; "15m" -> 900000L; else -> 3600000L }
        val json = getJson("/quote/v1/markPrice/klines?symbol=$symbol&interval=$interval&from=${now-duration*260}&to=$now&limit=260")
        val data = json.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<MarketCandle>()
        for (i in 0 until data.length()) {
            val row = data.getJSONObject(i)
            val o = row.optString("open").toDoubleOrNull() ?: continue
            val h = row.optString("high").toDoubleOrNull() ?: continue
            val l = row.optString("low").toDoubleOrNull() ?: continue
            val c = row.optString("close").toDoubleOrNull() ?: continue
            val v = row.optString("volume").toDoubleOrNull() ?: 0.0
            if (o > 0 && h > 0 && l > 0 && c > 0) out.add(MarketCandle(o,h,l,c,v))
        }
        if (out.size < 60) throw IllegalStateException("کندل OHLC کافی نیست")
        return out
    }

    private fun candles(symbol: String, interval: String): List<Double> {
        val now = System.currentTimeMillis()
        val duration = when (interval) { "1m" -> 60000L; "5m" -> 300000L; "15m" -> 900000L; else -> 3600000L }
        val url = "/quote/v1/markPrice/klines?symbol=$symbol&interval=$interval&from=${now-duration*260}&to=$now&limit=260"
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
