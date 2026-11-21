package com.cryptoscalp.app

/**
 * Single source of truth for the timeframe selection contract.
 *
 * Selection order is stable and independent from the order in which the user taps chips.
 * Empty selections are rejected rather than silently replaced with a default.
 */
object TimeframeSelection {
    val supported: List<String> = listOf("1m", "3m", "5m", "15m", "30m", "1h", "4h")

    private val durationByCode = mapOf(
        "1m" to 60_000L,
        "3m" to 180_000L,
        "5m" to 300_000L,
        "15m" to 900_000L,
        "30m" to 1_800_000L,
        "1h" to 3_600_000L,
        "4h" to 14_400_000L
    )

    fun normalize(selection: Collection<String>): List<String> {
        val requested = selection.map { it.trim().lowercase() }.toSet()
        require(requested.isNotEmpty()) { "حداقل یک تایم‌فریم باید انتخاب شود." }
        val unsupported = requested - supported.toSet()
        require(unsupported.isEmpty()) { "تایم‌فریم پشتیبانی‌نشده: ${unsupported.joinToString()}" }
        return supported.filter { it in requested }
    }

    fun durationMs(timeframe: String): Long =
        durationByCode[timeframe] ?: throw IllegalArgumentException("تایم‌فریم نامعتبر است: $timeframe")

    /**
     * Returns only fully closed candles for this timeframe.
     * Candle timestamps are candle-open timestamps; no other timeframe is consulted.
     */
    fun latestClosed(
        candles: List<MarketCandle>,
        timeframe: String,
        exchangeTimeMs: Long
    ): List<MarketCandle> {
        require(exchangeTimeMs > 0L) { "زمان مرجع صرافی نامعتبر است." }
        val duration = durationMs(timeframe)
        return candles.asSequence()
            .filter { it.time > 0L && it.time + duration <= exchangeTimeMs }
            .sortedBy { it.time }
            .toList()
    }

    /** The selected timeframe list is the complete analysis input; unselected data is discarded. */
    fun selectedData(
        data: Map<String, List<MarketCandle>>,
        selection: Collection<String>
    ): Map<String, List<MarketCandle>> {
        val selected = normalize(selection)
        val missing = selected.filterNot(data::containsKey)
        require(missing.isEmpty()) { "داده تایم‌فریم‌های انتخاب‌شده موجود نیست: ${missing.joinToString()}" }
        return selected.associateWith { data.getValue(it) }
    }
}
