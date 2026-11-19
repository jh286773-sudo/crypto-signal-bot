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
    private var selectedChartTimeframe = "5m"
    private val timeframeButtons = mutableMapOf<String, TextView>()

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
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_premium)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(8, 9, 12))
                setStroke(dp(1), goldLight)
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)).apply { marginEnd = dp(13) }
            elevation = dp(12).toFloat()
            contentDescription = "AURUM X"
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
        hero.addView(AurumHeroArt(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply { topMargin = dp(5); bottomMargin = dp(3) })
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
        visualCard.addView(marketChart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(232)).apply {
            topMargin = dp(5); bottomMargin = dp(8)
        })
        val visualFoot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        visualFoot.addView(text("بازه نمودار", 10, muted, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf("5m" to "۵ دقیقه", "15m" to "۱۵ دقیقه", "30m" to "۳۰ دقیقه").forEach { (tf, label) ->
            val chip = TextView(this).apply {
                text = label; textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                setPadding(dp(11), dp(8), dp(11), dp(8))
                setOnClickListener {
                    if (selectedChartTimeframe != tf) {
                        selectedChartTimeframe = tf
                        updateTimeframeStyles()
                        refreshMarketChart()
                    }
                }
            }
            timeframeButtons[tf] = chip
            visualFoot.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }
        updateTimeframeStyles()
        visualCard.addView(visualFoot)
        visualCard.addView(text("نمودار کندلی زنده بر اساس داده‌های واقعی Toobit", 9, muted))
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
        applyPremiumMotion(livePill)
    }

    private fun applyPremiumMotion(liveIndicator: TextView) {
        root.post {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                child.alpha = 0f
                child.translationY = dp(14).toFloat()
                child.animate().alpha(1f).translationY(0f)
                    .setStartDelay((index * 55L).coerceAtMost(440L))
                    .setDuration(420L).start()
            }
        }
        android.animation.ValueAnimator.ofFloat(1f, .72f, 1f).apply {
            duration = 1800L
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { liveIndicator.alpha = it.animatedValue as Float }
            start()
        }
        fun attachPressFeedback(view: android.view.View) {
            view.isHapticFeedbackEnabled = true
            view.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> target.animate().scaleX(.975f).scaleY(.975f)
                        .translationZ(dp(2).toFloat()).setDuration(85L).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        target.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(145L).start()
                }
                false
            }
        }
        fun visit(view: android.view.View) {
            if (view is Button) {
                attachPressFeedback(view)
                view.stateListAnimator = null
                view.elevation = max(view.elevation, dp(8).toFloat())
                view.minHeight = max(view.minHeight, dp(46))
                view.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                view.letterSpacing = 0.015f
            } else if (view.isClickable && view !is TextView) {
                attachPressFeedback(view)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }

    // High-resolution, density-aware metallic mascot artwork drawn natively (no blurry bitmap assets).
    private inner class AurumHeroArt(context: Context) : android.view.View(context) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG)
        init { setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null) }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w * .5f; val cy = h * .52f
            p.style = android.graphics.Paint.Style.FILL
            p.shader = android.graphics.RadialGradient(cx, cy, h * .72f,
                intArrayOf(Color.argb(65, 227, 177, 65), Color.argb(20, 180, 125, 35), Color.TRANSPARENT),
                floatArrayOf(0f, .62f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, h * .72f, p); p.shader = null

            // Organic bull silhouette: curved horns, rounded head, natural eyes and broad muzzle.
        p.style = android.graphics.Paint.Style.FILL
        p.shader = null

        // Broad, curved horns (avoid pointed polygon geometry).
        val horns = android.graphics.Path().apply {
            moveTo(cx-h*.245f, cy-h*.245f)
            cubicTo(cx-h*.48f, cy-h*.27f, cx-h*.57f, cy-h*.55f, cx-h*.68f, cy-h*.51f)
            cubicTo(cx-h*.66f, cy-h*.34f, cx-h*.55f, cy-h*.13f, cx-h*.30f, cy-h*.075f)
            cubicTo(cx-h*.38f, cy-h*.20f, cx-h*.39f, cy-h*.36f, cx-h*.245f, cy-h*.245f)
            close()
            moveTo(cx+h*.245f, cy-h*.245f)
            cubicTo(cx+h*.48f, cy-h*.27f, cx+h*.57f, cy-h*.55f, cx+h*.68f, cy-h*.51f)
            cubicTo(cx+h*.66f, cy-h*.34f, cx+h*.55f, cy-h*.13f, cx+h*.30f, cy-h*.075f)
            cubicTo(cx+h*.38f, cy-h*.20f, cx+h*.39f, cy-h*.36f, cx+h*.245f, cy-h*.245f)
            close()
        }
        p.shader = android.graphics.LinearGradient(0f, cy-h*.55f, 0f, cy-h*.05f,
            intArrayOf(Color.rgb(255,247,211), goldLight, gold, Color.rgb(91,59,17)),
            null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(horns, p); p.shader = null
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = dp(1.5f).toFloat()
        p.color = Color.rgb(255,235,174)
        canvas.drawPath(horns, p)
        p.style = android.graphics.Paint.Style.FILL

        // Soft, swept-back ears.
        val ears = android.graphics.Path().apply {
            moveTo(cx-h*.255f, cy-h*.12f)
            cubicTo(cx-h*.43f, cy-h*.25f, cx-h*.58f, cy-h*.18f, cx-h*.55f, cy+h*.015f)
            cubicTo(cx-h*.48f, cy+h*.12f, cx-h*.34f, cy+h*.08f, cx-h*.24f, cy+h*.035f)
            close()
            moveTo(cx+h*.255f, cy-h*.12f)
            cubicTo(cx+h*.43f, cy-h*.25f, cx+h*.58f, cy-h*.18f, cx+h*.55f, cy+h*.015f)
            cubicTo(cx+h*.48f, cy+h*.12f, cx+h*.34f, cy+h*.08f, cx+h*.24f, cy+h*.035f)
            close()
        }
        p.shader = android.graphics.LinearGradient(cx-h*.5f, cy-h*.1f, cx+h*.5f, cy+h*.1f,
            intArrayOf(Color.rgb(74,55,25), gold, Color.rgb(91,61,20)),
            null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(ears, p); p.shader = null

        // Rounded bovine head with a wide forehead and gently tapered jaw.
        val face = android.graphics.Path().apply {
            moveTo(cx, cy-h*.405f)
            cubicTo(cx-h*.22f, cy-h*.43f, cx-h*.34f, cy-h*.29f, cx-h*.345f, cy-h*.105f)
            cubicTo(cx-h*.36f, cy+h*.12f, cx-h*.27f, cy+h*.34f, cx-h*.12f, cy+h*.405f)
            cubicTo(cx-h*.055f, cy+h*.435f, cx+h*.055f, cy+h*.435f, cx+h*.12f, cy+h*.405f)
            cubicTo(cx+h*.27f, cy+h*.34f, cx+h*.36f, cy+h*.12f, cx+h*.345f, cy-h*.105f)
            cubicTo(cx+h*.34f, cy-h*.29f, cx+h*.22f, cy-h*.43f, cx, cy-h*.405f)
            close()
        }
        p.shader = android.graphics.LinearGradient(cx-h*.32f, cy-h*.35f, cx+h*.30f, cy+h*.40f,
            intArrayOf(Color.rgb(255,239,180), Color.rgb(191,143,52), Color.rgb(52,43,27), Color.rgb(214,166,66)),
            null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(face, p); p.shader = null
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = dp(2).toFloat()
        p.color = Color.rgb(255,225,139)
        canvas.drawPath(face, p)
        p.style = android.graphics.Paint.Style.FILL

        // Forehead ridge and soft cheek contours.
        val detail = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(1.4f).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = Color.argb(190, 255, 226, 151)
        }
        val contours = android.graphics.Path().apply {
            moveTo(cx, cy-h*.36f)
            cubicTo(cx-h*.10f, cy-h*.32f, cx-h*.12f, cy-h*.24f, cx-h*.13f, cy-h*.19f)
            moveTo(cx-h*.285f, cy+h*.015f)
            cubicTo(cx-h*.27f, cy+h*.16f, cx-h*.21f, cy+h*.25f, cx-h*.13f, cy+h*.29f)
            moveTo(cx+h*.285f, cy+h*.015f)
            cubicTo(cx+h*.27f, cy+h*.16f, cx+h*.21f, cy+h*.25f, cx+h*.13f, cy+h*.29f)
        }
        canvas.drawPath(contours, detail)

        // Natural dark eyes with curved golden brow ridges.
        p.color = Color.rgb(20,16,12)
        canvas.drawOval(cx-h*.245f, cy-h*.145f, cx-h*.105f, cy-h*.045f, p)
        canvas.drawOval(cx+h*.105f, cy-h*.145f, cx+h*.245f, cy-h*.045f, p)
        p.color = Color.rgb(255,210,104)
        canvas.drawOval(cx-h*.205f, cy-h*.125f, cx-h*.16f, cy-h*.065f, p)
        canvas.drawOval(cx+h*.16f, cy-h*.125f, cx+h*.205f, cy-h*.065f, p)
        detail.strokeWidth = dp(3).toFloat()
        detail.color = Color.rgb(255,220,135)
        val brows = android.graphics.Path().apply {
            moveTo(cx-h*.265f, cy-h*.17f); cubicTo(cx-h*.20f, cy-h*.225f, cx-h*.13f, cy-h*.21f, cx-h*.085f, cy-h*.16f)
            moveTo(cx+h*.265f, cy-h*.17f); cubicTo(cx+h*.20f, cy-h*.225f, cx+h*.13f, cy-h*.21f, cx+h*.085f, cy-h*.16f)
        }
        canvas.drawPath(brows, detail)

        // Broad rounded muzzle is the key recognizable bull feature.
        p.shader = android.graphics.LinearGradient(cx, cy+h*.12f, cx, cy+h*.42f,
            intArrayOf(Color.rgb(255,224,143), Color.rgb(177,124,39), Color.rgb(79,55,24)),
            null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawOval(cx-h*.255f, cy+h*.115f, cx+h*.255f, cy+h*.405f, p)
        p.shader = null
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = dp(1.5f).toFloat()
        p.color = Color.rgb(255,225,139)
        canvas.drawOval(cx-h*.255f, cy+h*.115f, cx+h*.255f, cy+h*.405f, p)
        p.style = android.graphics.Paint.Style.FILL
        p.color = Color.rgb(27,20,12)
        canvas.drawOval(cx-h*.17f, cy+h*.205f, cx-h*.075f, cy+h*.285f, p)
        canvas.drawOval(cx+h*.075f, cy+h*.205f, cx+h*.17f, cy+h*.285f, p)
        // Soft muzzle highlight and central philtrum.
        p.shader = android.graphics.LinearGradient(cx-h*.18f, cy+h*.14f, cx+h*.18f, cy+h*.22f,
            intArrayOf(Color.argb(0,255,245,205), Color.argb(170,255,239,190), Color.argb(0,255,245,205)),
            null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawOval(cx-h*.18f, cy+h*.14f, cx+h*.18f, cy+h*.22f, p)
        p.shader = null
        p.color = Color.rgb(255,220,132)
        canvas.drawLine(cx, cy+h*.285f, cx, cy+h*.35f, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = dp(1).toFloat()
        p.color = Color.argb(175,255,225,145)
        canvas.drawArc(cx-h*.12f, cy+h*.30f, cx+h*.12f, cy+h*.39f, 12f, 156f, false, p)
        p.style = android.graphics.Paint.Style.FILL

        }
    }

    private fun updateTimeframeStyles() {
        timeframeButtons.forEach { (tf, chip) ->
            val active = tf == selectedChartTimeframe
            chip.setTextColor(if (active) ink else goldLight)
            chip.background = if (active) rounded(goldLight, Color.WHITE, 10)
                else rounded(Color.rgb(27, 27, 29), Color.rgb(105, 84, 43), 10)
            chip.elevation = if (active) dp(5).toFloat() else 0f
        }
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
        val timeframe = selectedChartTimeframe
        marketSymbolLabel.text = symbol.removeSuffix("-SWAP-USDT") + "  •  TOOBIT / " + timeframe
        marketChangeLabel.text = "در حال دریافت…"
        thread(name = "toobit-chart") {
            try {
                val candles = ExchangeMarketData.candles("Toobit", symbol, timeframe)
                val last = candles.last()
                val first = candles[max(0, candles.size - 13)]
                val change = if (first.close == 0.0) 0.0 else (last.close - first.close) / first.close * 100.0
                runOnUiThread {
                    marketPriceLabel.text = fmt(last.close)
                    marketChangeLabel.text = String.format(java.util.Locale.US, "%+.2f%%", change)
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
        init {
            // Render the live chart with Android's hardware-accelerated vector canvas.
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            setWillNotDraw(false)
        }
        private val candles = mutableListOf<MarketCandle>()
        private val grid = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(48, 220, 185, 105); strokeWidth = dp(1).toFloat()
        }
        private val axisText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(155, 220, 205, 169); textSize = dp(9).toFloat()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val candleShadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 0, 0, 0); style = android.graphics.Paint.Style.FILL
        }
        private val volumePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 212, 175, 55); style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val wick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(1).toFloat(); strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(34, 218, 174, 75)
        }
        private val lastLine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 239, 198, 91)
            strokeWidth = dp(1).toFloat()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f)
        }
        private val areaPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun setCandles(values: List<MarketCandle>) {
            candles.clear()
            candles.addAll(values)
            invalidate()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val axisWidth = dp(43).toFloat()
            val plotRight = w - axisWidth
            val plotBottom = h - dp(24)
            for (i in 0..4) {
                val gy = dp(8).toFloat() + (plotBottom - dp(8)) * i / 4f
                canvas.drawLine(0f, gy, plotRight, gy, grid)
                
            }
            canvas.drawRoundRect(0f, 0f, w, h, dp(12).toFloat(), dp(12).toFloat(), borderPaint)
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
            axisText.textAlign = android.graphics.Paint.Align.RIGHT
            for (i in 0..4) {
                val gy = dp(8).toFloat() + (plotBottom - dp(8)) * i / 4f
                canvas.drawText(fmt(high - range * i / 4.0), w - dp(5).toFloat(), gy + dp(3), axisText)
            }
            axisText.textAlign = android.graphics.Paint.Align.LEFT
            val top = dp(10).toFloat(); val bottom = plotBottom - dp(3)
            fun y(value: Double) = bottom - (((value - low) / range).toFloat() * (bottom - top))
            val slot = plotRight / candles.size
            val candleWidth = (slot * .58f).coerceAtLeast(dp(2).toFloat())
            val maxVolume = candles.maxOf { it.volume }.coerceAtLeast(0.0000001)
            val closePath = android.graphics.Path()
            candles.forEachIndexed { index, c ->
                val x = slot * index + slot / 2f
                if (index == 0) closePath.moveTo(x, y(c.close)) else closePath.lineTo(x, y(c.close))
            }
            val areaPath = android.graphics.Path(closePath).apply {
                lineTo(slot * (candles.size - .5f), bottom)
                lineTo(slot * .5f, bottom)
                close()
            }
            areaPaint.shader = android.graphics.LinearGradient(0f, top, 0f, bottom,
                intArrayOf(Color.argb(42, 212, 175, 55), Color.argb(0, 212, 175, 55)),
                null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawPath(areaPath, areaPaint)
            candles.forEachIndexed { index, c ->
                val x = slot * index + slot / 2f
                val up = c.close >= c.open
                val color = if (up) Color.rgb(91, 211, 145) else Color.rgb(231, 91, 83)
                volumePaint.color = if (up) Color.argb(55, 91, 211, 145) else Color.argb(55, 231, 91, 83)
                val volumeHeight = (c.volume / maxVolume).toFloat() * dp(17)
                canvas.drawRoundRect(x - candleWidth * .36f, h - volumeHeight - dp(3), x + candleWidth * .36f, h - dp(3),
                    dp(2).toFloat(), dp(2).toFloat(), volumePaint)
                wick.color = color
                body.color = color
                canvas.drawLine(x + dp(1), y(c.high) + dp(1), x + dp(1), y(c.low) + dp(1), candleShadow)
                canvas.drawLine(x, y(c.high), x, y(c.low), wick)
                val y1 = y(max(c.open, c.close))
                val y2 = y(min(c.open, c.close))
                val rectTop = min(y1, y2)
                val rectBottom = max(y1, y2).let { if (it - rectTop < dp(2)) rectTop + dp(2) else it }
                canvas.drawRoundRect(x - candleWidth / 2f, rectTop, x + candleWidth / 2f, rectBottom,
                    dp(1).toFloat(), dp(1).toFloat(), body)
            }
            val lastY = y(candles.last().close)
            canvas.drawLine(0f, lastY, w, lastY, lastLine)
            canvas.drawCircle(plotRight - dp(3).toFloat(), lastY, dp(10).toFloat(), glow)
            val marker = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(245, 220, 135)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(1).toFloat()
            }
            canvas.drawCircle(plotRight - dp(3).toFloat(), lastY, dp(3).toFloat(), marker)
            axisText.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.drawText(fmt(candles.last().close), w - dp(5).toFloat(), (lastY - dp(5)).coerceAtLeast(dp(10).toFloat()), axisText)
            axisText.textAlign = android.graphics.Paint.Align.LEFT
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

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
            intArrayOf(Color.rgb(83, 65, 31), Color.rgb(42, 36, 25), Color.rgb(23, 24, 28), Color.rgb(8, 10, 14))
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.rgb(171, 132, 54))
        }
        elevation = dp(12).toFloat()
        translationZ = dp(1).toFloat()
        clipToOutline = true
        stateListAnimator = null
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
        val base = palette[(label.hashCode() and 0x7fffffff) % palette.size]
        val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        paint.shader = android.graphics.RadialGradient(cx * .72f, cy * .68f, sizePx * .72f,
            intArrayOf(Color.WHITE, base, Color.rgb(18, 20, 25)), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, sizePx * .48f, paint)
        paint.shader = null
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = (sizePx * .035f).coerceAtLeast(1f)
        paint.color = Color.rgb(245, 220, 135)
        canvas.drawCircle(cx, cy, sizePx * .455f, paint)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.setShadowLayer(sizePx * .035f, 0f, sizePx * .025f, Color.BLACK)
        paint.textSize = sizePx * if (label.length > 3) .25f else .32f
        val y = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, cx, y, paint)
        paint.clearShadowLayer()
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
                        val scaled = android.graphics.Bitmap.createScaledBitmap(decoded, dp(64), dp(64), true)
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
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            includeFontPadding = false
            letterSpacing = if (size <= 11) 0.025f else 0.008f
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD)
                else Typeface.create("sans-serif", Typeface.NORMAL)
            if (bold && (color == gold || color == goldLight)) {
                setShadowLayer(dp(5).toFloat(), 0f, dp(2).toFloat(), Color.argb(115, 185, 132, 34))
            }
            setPadding(0, dp(8), 0, dp(8))
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
