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
import java.util.concurrent.Executors
import java.util.Collections

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var coinPickerButton: Button
    private val selectedExchanges = linkedSetOf("Toobit")
    private var coinChoices: ListView? = null
    private var coinAdapter: CoinListAdapter? = null
    private val logoExecutor = Executors.newFixedThreadPool(4)
    private val failedLogoUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private val symbols = mutableListOf<String>()
    private val selectedSymbols = linkedSetOf<String>()
    private val channelId = "scalp_signals"
    private val logoCache = mutableMapOf<String, android.graphics.Bitmap>()
    private lateinit var marketChart: MarketVisualView
    private lateinit var marketPriceLabel: TextView
    private lateinit var marketSymbolLabel: TextView
    private lateinit var marketChangeLabel: TextView

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
        window.statusBarColor = Color.rgb(5, 6, 9)
        window.navigationBarColor = Color.rgb(5, 6, 9)
        window.decorView.systemUiVisibility = 0
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
            setBackgroundResource(R.drawable.app_background)
        }

        // Metallic brand header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(18))
        }
        val mark = TextView(this).apply {
            text = "♛\nAX"
            textSize = 17f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.rgb(20, 16, 8))
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.WHITE, goldLight, gold, Color.rgb(111, 76, 17))
            ).apply { cornerRadius = dp(17).toFloat(); setStroke(dp(1), Color.rgb(255, 235, 169)) }
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)).apply { marginEnd = dp(13) }
            elevation = dp(12).toFloat()
        }
        header.addView(mark)
        val brandBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandBlock.addView(text("GOLD EDITION  •  FUTURES", 9, gold, true))
        brandBlock.addView(text("AURUM X", 28, goldLight, true))
        brandBlock.addView(text("by JAVAD  |  دستیار هوشمند بازار", 11, muted))
        header.addView(brandBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = Button(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(goldLight)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(63, 55, 37), Color.rgb(20, 21, 25), Color.rgb(9, 10, 13))
            ).apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), gold) }
            elevation = dp(9).toFloat()
            minWidth = dp(50); minimumHeight = dp(50)
            setPadding(0, 0, 0, 0)
        }
        header.addView(settings)
        settings.setOnClickListener { showMonitorSettings(settings) }
        root.addView(header)

        // Raised live-status panel
        val hero = card()
        hero.setPadding(dp(18), dp(17), dp(18), dp(18))
        hero.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(73, 56, 24), Color.rgb(35, 30, 22), Color.rgb(16, 18, 23), Color.rgb(8, 10, 14))
        ).apply { cornerRadius = dp(23).toFloat(); setStroke(dp(1), Color.rgb(221, 177, 73)) }
        hero.elevation = dp(13).toFloat()
        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroTop.addView(text("وضعیت اتصال بازار", 15, goldLight, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val livePill = TextView(this).apply {
            text = "●  LIVE"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(123, 255, 174))
            background = rounded(Color.rgb(15, 55, 37), Color.rgb(54, 167, 103), 20)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            elevation = dp(4).toFloat()
        }
        heroTop.addView(livePill)
        hero.addView(heroTop)
        status = text("در حال اتصال به Toobit…", 14, Color.WHITE, true).apply {
            setPadding(0, dp(13), 0, dp(4))
            setShadowLayer(8f, 0f, 0f, Color.rgb(174, 128, 36))
        }
        hero.addView(status)
        hero.addView(text("داده‌های بازار فیوچرز  •  منبع رسمی تحلیل: Toobit", 10, goldLight))
        root.addView(hero, marginParams(bottom = 16))

        // Premium visual dashboard. The artwork is decorative, not fabricated market data.
        val visualCard = card().apply {
            setPadding(dp(17), dp(16), dp(17), dp(15))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(43, 36, 22), Color.rgb(20, 21, 26), Color.rgb(8, 10, 14))
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(145, 108, 42)) }
            elevation = dp(12).toFloat()
        }
        val visualHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val visualTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        visualTitle.addView(text("MARKET INTELLIGENCE", 9, gold, true))
        visualTitle.addView(text("نمای حرفه‌ای بازار", 18, Color.WHITE, true))
        visualHeader.addView(visualTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        visualHeader.addView(TextView(this).apply {
            text = "TOOBIT  /  FUTURES"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(goldLight); background = rounded(Color.rgb(38, 33, 23), gold, 18)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        })
        visualCard.addView(visualHeader)
        val quoteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val quoteInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        marketSymbolLabel = text("در انتظار داده بازار", 10, muted, true)
        marketPriceLabel = text("—", 25, Color.WHITE, true).apply {
            setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.rgb(165, 119, 31))
        }
        quoteInfo.addView(marketSymbolLabel)
        quoteInfo.addView(marketPriceLabel)
        quoteRow.addView(quoteInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        marketChangeLabel = text("دریافت کندل…", 11, goldLight, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(38, 33, 23), gold, 12)
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }
        quoteRow.addView(marketChangeLabel)
        visualCard.addView(quoteRow)
        marketChart = MarketVisualView(this)
        visualCard.addView(marketChart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(142)).apply {
            topMargin = dp(5); bottomMargin = dp(8)
        })
        val visualFoot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        visualFoot.addView(text("TIMEFRAMES", 9, muted, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf("5 MIN", "15 MIN", "30 MIN").forEachIndexed { index, label ->
            visualFoot.addView(TextView(this).apply {
                text = label; textSize = 8f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (index == 1) ink else goldLight)
                background = if (index == 1) rounded(gold, goldLight, 9) else rounded(Color.rgb(30, 29, 25), Color.rgb(105, 84, 43), 9)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        }
        visualCard.addView(visualFoot)
        visualCard.addView(text("تصویرسازی گرافیکی رابط؛ داده و سیگنال واقعی فقط پس از دریافت اطلاعات Toobit.", 9, muted))
        root.addView(visualCard, marginParams(bottom = 16))

        // Exchange identity card
        val exchangeCard = card()
        val exchangeHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val exchangeIcon = TextView(this).apply {
            text = "T"
            textSize = 21f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(52, 160, 143), Color.rgb(13, 68, 66), Color.rgb(7, 29, 34))
            ).apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(112, 220, 191)) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(13) }
            elevation = dp(8).toFloat()
        }
        exchangeHead.addView(exchangeIcon)
        val exchangeInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        exchangeInfo.addView(text("منبع داده و تحلیل", 11, muted, true))
        exchangeInfo.addView(text("TOOBIT  •  PERPETUAL", 17, goldLight, true))
        exchangeHead.addView(exchangeInfo)
        exchangeCard.addView(exchangeHead)
        exchangeCard.addView(text("قیمت، کندل و تحلیل بازار فقط از Toobit دریافت می‌شود.", 11, muted))
        root.addView(exchangeCard, marginParams(bottom = 15))

        // Asset selector
        val selectCard = card()
        selectCard.addView(text("دارایی‌های تحت نظر", 17, Color.WHITE, true))
        selectCard.addView(text("ارزهای فیوچرز موردنظر را برای پایش انتخاب کن.", 11, muted))
        coinPickerButton = Button(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(30, 22, 8))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.WHITE, goldLight, gold, Color.rgb(155, 105, 24))
            ).apply { cornerRadius = dp(15).toFloat(); setStroke(dp(1), Color.rgb(255, 231, 157)) }
            elevation = dp(10).toFloat()
            setPadding(dp(14), dp(9), dp(14), dp(9))
            minHeight = dp(50)
        }
        selectCard.addView(coinPickerButton, marginParams(top = 10))
        renderSelectedChoices()
        coinPickerButton.setOnClickListener { showCoinPicker() }
        root.addView(selectCard, marginParams(bottom = 15))

        // Monitoring engine panel
        val monitorCard = card()
        val monitorHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val monitorMark = TextView(this).apply {
            text = "◈"
            textSize = 27f
            setTextColor(goldLight)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(84, 66, 29), Color.rgb(23, 23, 25))
            ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(1), gold) }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(11) }
            elevation = dp(7).toFloat()
        }
        monitorHeader.addView(monitorMark)
        monitorHeader.addView(text("موتور پایش خودکار", 16, Color.WHITE, true))
        monitorCard.addView(monitorHeader)
        monitorCard.addView(text("پایش پس‌زمینه  •  ۱۰ ابزار تحلیلی  •  اسمارت‌مانی  •  هشدار لانگ و شورت", 11, muted))
        root.addView(monitorCard, marginParams(bottom = 17))

        val disclaimer = text("فقط تحلیل و هشدار؛ هیچ سفارشی ثبت نمی‌شود. سطوح قیمتی تخمینی‌اند و سود تضمین‌شده نیست.", 10, muted)
        disclaimer.gravity = Gravity.CENTER
        root.addView(disclaimer)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundResource(R.drawable.app_background)
            addView(root)
        })
    }

    private fun refreshMarketChart() {
        if (!::marketChart.isInitialized) return
        val symbol = selectedSymbols.firstOrNull() ?: symbols.firstOrNull()
        if (symbol == null) {
            marketSymbolLabel.text = "ابتدا یک دارایی انتخاب کن"
            marketPriceLabel.text = "—"
            marketChangeLabel.text = "بدون نماد"
            marketChart.setCandles(emptyList())
            return
        }
        marketSymbolLabel.text = "${symbol.removeSuffix("-SWAP-USDT")}  •  TOOBIT / 5m"
        marketChangeLabel.text = "در حال دریافت…"
        thread(name = "toobit-chart") {
            try {
                val candles = ExchangeMarketData.candles("Toobit", symbol, "5m")
                val last = candles.last()
                val first = candles[max(0, candles.size - 13)]
                val change = if (first.close == 0.0) 0.0 else (last.close - first.close) / first.close * 100.0
                runOnUiThread {
                    marketPriceLabel.text = fmt(last.close)
                    marketChangeLabel.text = String.format(java.util.Locale.US, "%+.2f%% / 1h", change)
                    marketChangeLabel.setTextColor(if (change >= 0) Color.rgb(112, 231, 157) else Color.rgb(255, 119, 108))
                    marketChart.setCandles(candles.takeLast(36))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    marketChangeLabel.text = "داده در دسترس نیست"
                    marketPriceLabel.text = "—"
                    marketChart.setCandles(emptyList())
                }
            }
        }
    }

    // Native candle renderer: plots actual Toobit OHLC values, not decorative sample data.
    private inner class MarketVisualView(context: Context) : android.view.View(context) {
        private val candles = mutableListOf<MarketCandle>()
        private val grid = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 220, 185, 105); strokeWidth = dp(1).toFloat()
        }
        private val wick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(1.4f.toInt()).toFloat(); strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(22, 218, 174, 75)
        }
        fun setCandles(values: List<MarketCandle>) {
            candles.clear()
            candles.addAll(values)
            invalidate()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            for (i in 0..4) canvas.drawLine(0f, h * i / 4f, w, h * i / 4f, grid)
            if (candles.size < 2) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = muted; textSize = dp(11).toFloat(); textAlign = android.graphics.Paint.Align.CENTER
                }
                canvas.drawText("نمودار پس از دریافت داده Toobit نمایش داده می‌شود", w / 2f, h / 2f, p)
                return
            }
            val low = candles.minOf { it.low }
            val high = candles.maxOf { it.high }
            val range = (high - low).takeIf { it > 0.0 } ?: (high * .001).coerceAtLeast(.0000001)
            val top = dp(9).toFloat(); val bottom = h - dp(9)
            fun y(value: Double) = bottom - (((value - low) / range).toFloat() * (bottom - top))
            val slot = w / candles.size
            val candleWidth = (slot * .56f).coerceAtLeast(dp(2).toFloat())
            candles.forEachIndexed { index, c ->
                val x = slot * index + slot / 2f
                val up = c.close >= c.open
                val color = if (up) Color.rgb(91, 211, 145) else Color.rgb(231, 91, 83)
                wick.color = color
                body.color = color
                canvas.drawLine(x, y(c.high), x, y(c.low), wick)
                val y1 = y(max(c.open, c.close))
                val y2 = y(min(c.open, c.close))
                val rectTop = min(y1, y2)
                val rectBottom = max(y1, y2).let { if (it - rectTop < dp(2)) rectTop + dp(2) else it }
                canvas.drawRoundRect(x - candleWidth / 2f, rectTop, x + candleWidth / 2f, rectBottom,
                    dp(1).toFloat(), dp(1).toFloat(), body)
            }
            val lastY = y(candles.last().close)
            canvas.drawCircle(w - dp(3).toFloat(), lastY, dp(4).toFloat(), glow)
        }
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
        if (failedLogoUrls.contains(imageUrl)) return
        logoExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(imageUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "AURUM-X-Android")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    failedLogoUrls.add(imageUrl)
                    return@execute
                }
                val bitmap = connection.inputStream.use {
                    BitmapFactory.decodeStream(it)?.let { decoded ->
                        val scaled = android.graphics.Bitmap.createScaledBitmap(decoded, dp(48), dp(48), true)
                        if (scaled !== decoded) decoded.recycle()
                        scaled
                    }
                }
                if (bitmap != null) {
                    synchronized(logoCache) { logoCache[imageUrl] = bitmap }
                    runOnUiThread { if (view.tag == imageUrl) view.setImageBitmap(bitmap) }
                } else failedLogoUrls.add(imageUrl)
            } catch (_: Exception) {
                failedLogoUrls.add(imageUrl)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun logoView(size: Int): ImageView = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(14) }
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(66, 56, 35), Color.rgb(18, 20, 24))
        ).apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), gold) }
        elevation = dp(7).toFloat()
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }

    private fun showCoinPicker() {
        val picker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(57, 47, 27), Color.rgb(25, 25, 29), Color.rgb(8, 10, 14))
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), gold) }
            elevation = dp(18).toFloat()
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
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(43, 40, 32), Color.rgb(15, 17, 21))
            ).apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(143, 111, 47)) }
            elevation = dp(5).toFloat()
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        picker.addView(search, marginParams(top = 8, bottom = 8))

        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(67, 59, 43))
            dividerHeight = dp(1)
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = true
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.48).toInt())
        }
        picker.addView(list)
        coinChoices = list
        coinAdapter = CoinListAdapter()
        list.adapter = coinAdapter
        renderCoinChoices("")
        list.setOnItemClickListener { _, _, position, _ ->
            val symbol = coinAdapter?.getItem(position) ?: return@setOnItemClickListener
            if (selectedSymbols.contains(symbol)) selectedSymbols.remove(symbol) else selectedSymbols.add(symbol)
            getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE).edit()
                .putStringSet("selected_symbols", selectedSymbols.toSet()).apply()
            countView.text = "${selectedSymbols.size} انتخاب"
            coinAdapter?.notifyDataSetChanged()
            renderSelectedChoices()
        }
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
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(65, 58, 43), Color.rgb(24, 25, 29))
            ).apply { cornerRadius = dp(13).toFloat(); setStroke(dp(1), gold) }
            elevation = dp(8).toFloat()
            setPadding(dp(10), 0, dp(10), 0)
        }
        footer.addView(clear, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        val done = Button(this).apply {
            text = "تأیید انتخاب‌ها"
            textSize = 13f
            setTextColor(ink)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.WHITE, goldLight, gold, Color.rgb(139, 94, 20))
            ).apply { cornerRadius = dp(13).toFloat(); setStroke(dp(1), Color.rgb(255, 234, 166)) }
            elevation = dp(11).toFloat()
        }
        footer.addView(done, LinearLayout.LayoutParams(0, dp(44), 1f))
        picker.addView(footer, marginParams(top = 10))
        val dialog = AlertDialog.Builder(this).setView(picker).create()
        clear.setOnClickListener {
            selectedSymbols.clear()
            getSharedPreferences("crypto_monitor", Context.MODE_PRIVATE).edit().putStringSet("selected_symbols", emptySet()).apply()
            countView.text = "0 انتخاب"
            coinAdapter?.notifyDataSetChanged()
            renderSelectedChoices()
        }
        done.setOnClickListener { dialog.dismiss(); refreshMarketChart() }
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
                    refreshMarketChart()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطای اتصال به ${selectedExchanges.firstOrNull()}: ${e.message}" }
            }
        }
    }

    private inner class CoinListAdapter : android.widget.BaseAdapter() {
        private var items: List<String> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): String = items[position]
        override fun getItemId(position: Int): Long = items[position].hashCode().toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val holder: CoinRowHolder
            val row: LinearLayout
            if (convertView == null) {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(5), dp(10), dp(5))
                }
                val logo = logoView(38)
                row.addView(logo)
                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val name = text("", 15, Color.WHITE, true)
                val detail = text("USDT PERPETUAL", 9, muted)
                info.addView(name)
                info.addView(detail)
                row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val dot = TextView(this@MainActivity).apply {
                    text = "●"; textSize = 24f; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                }
                row.addView(dot)
                holder = CoinRowHolder(logo, name, dot)
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as CoinRowHolder
            }

            val symbol = getItem(position)
            val base = symbol.removeSuffix("-SWAP-USDT")
            val selected = selectedSymbols.contains(symbol)
            holder.name.text = base
            holder.name.setTextColor(if (selected) goldLight else Color.WHITE)
            holder.dot.setTextColor(if (selected) gold else Color.TRANSPARENT)
            holder.dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (selected) gold else Color.TRANSPARENT)
                setStroke(dp(2), gold)
            }
            row.background = rounded(if (selected) Color.rgb(38, 34, 22) else panel,
                if (selected) gold else Color.rgb(100, 91, 70), 14)
            loadLogo(holder.logo, coinLogoUrl(base), base)
            return row
        }

        fun filter(query: String) {
            val q = query.trim().uppercase()
            items = symbols.filter { symbol ->
                val base = symbol.removeSuffix("-SWAP-USDT")
                q.isEmpty() || base.contains(q) || symbol.contains(q)
            }
            notifyDataSetChanged()
        }
    }

    private data class CoinRowHolder(
        val logo: ImageView,
        val name: TextView,
        val dot: TextView
    )

    private fun renderCoinChoices(query: String) {
        coinAdapter?.filter(query)
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
