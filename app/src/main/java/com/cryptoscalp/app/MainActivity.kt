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
import android.graphics.BitmapFactory
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
    private lateinit var coinPickerButton: Button
    private val selectedExchanges = linkedSetOf("Toobit")
    private var coinChoices: LinearLayout? = null
    private val symbols = mutableListOf<String>()
    private val selectedSymbols = linkedSetOf<String>()
    private val channelId = "scalp_signals"
    private val logoCache = mutableMapOf<String, android.graphics.Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        buildUi()
        val prefs = getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("manual_selection_initialized", false)) {
            prefs.edit().remove("selected_symbols").putBoolean("manual_selection_initialized", true).apply()
        }
        selectedExchanges.clear()
        selectedExchanges.add("Toobit")
        prefs.edit().putString("selected_exchange", "Toobit")
            .putStringSet("selected_exchanges", setOf("Toobit")).apply()
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
        window.statusBarColor = Color.rgb(8, 10, 15)
        window.navigationBarColor = Color.rgb(8, 10, 15)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
            setBackgroundResource(R.drawable.app_background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(20))
        }
        val mark = TextView(this).apply {
            text = "AX"; textSize = 20f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(ink); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(goldLight, gold, Color.rgb(137, 99, 25))
            ).apply { cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(13) }
            elevation = dp(7).toFloat()
        }
        header.addView(mark)
        val brandBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandBlock.addView(text("GOLD  •  CRYPTO", 10, gold, true))
        brandBlock.addView(text("AURUM X", 27, goldLight, true))
        brandBlock.addView(text("by JAVAD  |  تحلیل هوشمند بازار", 11, muted))
        header.addView(brandBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = Button(this).apply {
            text = "⚙"; textSize = 21f; setTextColor(goldLight)
            background = rounded(Color.rgb(27, 28, 33), Color.rgb(105, 86, 39), 16)
            elevation = dp(3).toFloat(); minWidth = dp(50); minimumHeight = dp(50)
            setPadding(0, 0, 0, 0)
        }
        header.addView(settings)
        settings.setOnClickListener { showMonitorSettings(settings) }
        root.addView(header)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(38, 33, 21), Color.rgb(20, 22, 28), Color.rgb(14, 16, 22))
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(126, 99, 42)) }
            elevation = dp(5).toFloat()
        }
        val heroTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroTop.addView(text("وضعیت اتصال بازار", 14, goldLight, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val livePill = TextView(this).apply {
            text = "●  LIVE"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(116, 220, 157))
            background = rounded(Color.rgb(22, 49, 39), Color.rgb(48, 111, 76), 20)
            setPadding(dp(11), dp(5), dp(11), dp(5))
        }
        heroTop.addView(livePill); hero.addView(heroTop)
        status = text("در حال اتصال به Toobit…", 13, Color.WHITE, true).apply { setPadding(0, dp(12), 0, dp(3)) }
        hero.addView(status)
        hero.addView(text("داده‌های بازار فیوچرز • منبع رسمی برنامه: Toobit", 10, muted))
        root.addView(hero, marginParams(bottom = 14))

        val exchangeCard = card()
        val exchangeHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val exchangeIcon = TextView(this).apply {
            text = "T"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); background = rounded(Color.rgb(37, 105, 91), Color.rgb(78, 170, 143), 12)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
        }
        exchangeHead.addView(exchangeIcon)
        val exchangeInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        exchangeInfo.addView(text("منبع داده و تحلیل", 11, muted, true))
        exchangeInfo.addView(text("TOOBIT", 19, goldLight, true))
        exchangeHead.addView(exchangeInfo); exchangeCard.addView(exchangeHead)
        exchangeCard.addView(text("قیمت، کندل و تحلیل بازار فقط از Toobit دریافت می‌شود.", 11, muted))
        root.addView(exchangeCard, marginParams(bottom = 14))

        val selectCard = card()
        selectCard.addView(text("دارایی‌های تحت نظر", 16, Color.WHITE, true))
        selectCard.addView(text("ارزهای فیوچرز موردنظر را برای پایش انتخاب کن.", 11, muted))
        coinPickerButton = Button(this).apply {
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(goldLight, gold)
            ).apply { cornerRadius = dp(14).toFloat() }
            elevation = dp(4).toFloat(); setPadding(dp(14), dp(9), dp(14), dp(9)); minHeight = dp(48)
        }
        selectCard.addView(coinPickerButton, marginParams(top = 10))
        renderSelectedChoices()
        coinPickerButton.setOnClickListener { showCoinPicker() }
        root.addView(selectCard, marginParams(bottom = 14))

        val monitorCard = card()
        val monitorHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val monitorMark = TextView(this).apply {
            text = "◈"; textSize = 24f; setTextColor(gold); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) }
        }
        monitorHeader.addView(monitorMark)
        monitorHeader.addView(text("موتور پایش خودکار", 15, Color.WHITE, true))
        monitorCard.addView(monitorHeader)
        monitorCard.addView(text("پایش پس‌زمینه • ۱۰ ابزار تحلیلی • اسمارت‌مانی • هشدار لانگ و شورت", 11, muted))
        root.addView(monitorCard, marginParams(bottom = 16))

        val disclaimer = text("فقط تحلیل و هشدار؛ هیچ سفارشی ثبت نمی‌شود. سطوح قیمتی تخمینی‌اند و سود تضمین‌شده نیست.", 10, muted)
        disclaimer.gravity = Gravity.CENTER
        root.addView(disclaimer)
        setContentView(ScrollView(this).apply {
            isFillViewport = true; clipToPadding = false
            setBackgroundResource(R.drawable.app_background); addView(root)
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
        setPadding(dp(17), dp(15), dp(17), dp(17))
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(31, 32, 37), Color.rgb(19, 21, 27))
        ).apply { cornerRadius = dp(19).toFloat(); setStroke(dp(1), Color.rgb(75, 66, 46)) }
        elevation = dp(4).toFloat()
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

    private fun coinLogoUrl(base: String): String =
        "https://raw.githubusercontent.com/spothq/cryptocurrency-icons/master/128/color/${base.lowercase()}.png"

    private fun fallbackCoinLogo(symbol: String, sizePx: Int): android.graphics.Bitmap {
        val label = symbol.substringBefore("-").take(4).uppercase().ifBlank { "?" }
        val palette = intArrayOf(Color.rgb(188, 145, 42), Color.rgb(45, 105, 155), Color.rgb(42, 135, 105), Color.rgb(125, 78, 150), Color.rgb(170, 82, 65))
        val color = palette[(label.hashCode() and 0x7fffffff) % palette.size]
        val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx * .48f, paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.textSize = sizePx * if (label.length > 3) .27f else .34f
        val y = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, sizePx / 2f, y, paint)
        return bitmap
    }

    private fun loadLogo(view: ImageView, imageUrl: String, symbol: String = "?") {
        view.tag = imageUrl
        val fallback = fallbackCoinLogo(symbol, dp(64))
        view.setImageBitmap(fallback)
        if (imageUrl.isBlank()) return
        synchronized(logoCache) { logoCache[imageUrl] }?.let {
            view.setImageBitmap(it)
            return
        }
        thread {
            try {
                val connection = (URL(imageUrl).openConnection() as HttpURLConnection)
                connection.connectTimeout = 8000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "AURUM-X-Android")
                val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                connection.disconnect()
                if (bitmap != null) {
                    synchronized(logoCache) { logoCache[imageUrl] = bitmap }
                    runOnUiThread { if (view.tag == imageUrl) view.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) { }
        }
    }

    private fun logoView(size: Int): ImageView = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(14) }
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = rounded(Color.rgb(22, 23, 27), gold, 12)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }

    private fun showCoinPicker() {
        val picker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(ink, gold, 20)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(text("انتخاب دارایی‌ها", 19, goldLight, true))
        titleBlock.addView(text("جستجو و انتخاب دستی ارزها", 11, muted))
        heading.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val countView = text("${selectedSymbols.size} انتخاب", 12, gold, true).apply { gravity = Gravity.CENTER }
        heading.addView(countView)
        picker.addView(heading)

        val search = EditText(this).apply {
            hint = "جستجوی ارز؛ مثل BTC یا SOL"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            background = rounded(panel, Color.rgb(105, 86, 39), 14)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        picker.addView(search, marginParams(top = 8, bottom = 8))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.48).toInt())
        }
        picker.addView(scroll)
        coinChoices = list
        renderCoinChoices("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderCoinChoices(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clear = Button(this).apply {
            text = "پاک‌کردن"
            textSize = 12f
            setTextColor(goldLight)
            background = rounded(panel, gold, 12)
            setPadding(dp(10), 0, dp(10), 0)
        }
        footer.addView(clear, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        val done = Button(this).apply {
            text = "تأیید انتخاب‌ها"
            textSize = 13f
            setTextColor(ink)
            background = rounded(gold, goldLight, 12)
        }
        footer.addView(done, LinearLayout.LayoutParams(0, dp(44), 1f))
        picker.addView(footer, marginParams(top = 10))
        val dialog = AlertDialog.Builder(this).setView(picker).create()
        clear.setOnClickListener {
            selectedSymbols.clear()
            getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE).edit().putStringSet("selected_symbols", emptySet()).apply()
            countView.text = "0 انتخاب"
            renderCoinChoices(search.text.toString())
            renderSelectedChoices()
        }
        done.setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
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
                val exchange = selectedExchanges.firstOrNull() ?: "Toobit"
                val ordered = ExchangeMarketData.contracts(exchange)
                // Do not label an exchange connected just because its contract list endpoint responds.
                val candleProbeError = if (ordered.isNotEmpty()) {
                    try { ExchangeMarketData.candles(exchange, ordered.first(), "5m"); null }
                    catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                } else "فهرست قراردادها خالی است"
                runOnUiThread {
                    symbols.clear()
                    symbols.addAll(ordered)
                    selectedSymbols.retainAll(ordered.toSet())
                    val prefs = getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE)
                    prefs.edit().putStringSet("selected_symbols", selectedSymbols.toSet()).apply()
                    renderSelectedChoices()
                    status.text = if (candleProbeError != null) {
                        "$exchange • فهرست ${ordered.size} قرارداد دریافت شد، اما دریافت کندل ناموفق بود: $candleProbeError"
                    } else if (selectedSymbols.isEmpty()) {
                        "$exchange متصل شد • ${ordered.size} قرارداد؛ ارزها را دستی انتخاب کن."
                    } else {
                        "$exchange متصل است • ${ordered.size} قرارداد؛ پایش خودکار ${selectedSymbols.size} ارز"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطای اتصال به ${selectedExchanges.firstOrNull()}: ${e.message}" }
            }
        }
    }

    private fun renderCoinChoices(query: String) {
        val choices = coinChoices ?: return
        val q = query.trim().uppercase()
        choices.removeAllViews()
        val matches = symbols.filter { symbol ->
            val base = symbol.removeSuffix("-SWAP-USDT")
            q.isEmpty() || base.contains(q) || symbol.contains(q)
        }
        for (symbol in matches) {
            val base = symbol.removeSuffix("-SWAP-USDT")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(10), dp(4))
                background = rounded(if (selectedSymbols.contains(symbol)) Color.rgb(38, 34, 22) else panel,
                    if (selectedSymbols.contains(symbol)) gold else Color.rgb(58, 59, 63), 14)
            }
            val coinLogo = logoView(38)
            row.addView(coinLogo)
            loadLogo(coinLogo, coinLogoUrl(base), base)
            val coinInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            coinInfo.addView(text(base, 15, if (selectedSymbols.contains(symbol)) goldLight else Color.WHITE, true))
            coinInfo.addView(text("USDT PERPETUAL", 9, muted))
            row.addView(coinInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val selectedDot = TextView(this).apply {
                text = "●"; textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(if (selectedSymbols.contains(symbol)) gold else Color.TRANSPARENT)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (selectedSymbols.contains(symbol)) gold else Color.TRANSPARENT)
                    setStroke(dp(2), gold)
                }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }
            row.addView(selectedDot)
            row.setOnClickListener {
                if (selectedSymbols.contains(symbol)) selectedSymbols.remove(symbol) else selectedSymbols.add(symbol)
                getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE).edit().putStringSet("selected_symbols", selectedSymbols.toSet()).apply()
                renderSelectedChoices(); renderCoinChoices(query)
            }
            choices.addView(row, marginParams(bottom = 7))
        }
        if (matches.isEmpty()) {
            val empty = text(if (symbols.isEmpty()) "در حال دریافت فهرست ارزها…" else "ارزی با این نماد پیدا نشد.", 13, muted)
            empty.gravity = Gravity.CENTER
            choices.addView(empty)
        }
    }

    private fun renderSelectedChoices() {
        if (!::coinPickerButton.isInitialized) return
        val count = selectedSymbols.size
        val names = selectedSymbols.map { it.removeSuffix("-SWAP-USDT") }
        coinPickerButton.text = if (count == 0) "انتخاب ارزها (هیچ ارزی انتخاب نشده)" else "انتخاب ارزها • $count انتخاب‌شده: " + names.take(5).joinToString("، ") + if (count > 5) " و ${count - 5} ارز دیگر" else ""
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
