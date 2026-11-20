package com.cryptoscalp.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AURUM X market-data gateway.
 * Toobit is the sole supported exchange; candle data is consumed by the strategy
 * in oldest-to-newest order.
 */
object ExchangeMarketData {
    val supported = listOf("Toobit")
    @Volatile private var serverOffsetMs: Long? = null
    @Volatile private var serverTimeSyncLocalMs: Long = 0L

    /** Exchange-clock estimate, synchronized periodically and shared by all market requests. */
    @Synchronized
    fun exchangeTimeMs(): Long {
        val before = System.currentTimeMillis()
        val offset = serverOffsetMs
        if (offset == null || before - serverTimeSyncLocalMs > 30_000L || before < serverTimeSyncLocalMs) {
            val response = JSONObject(get("https://api.toobit.com/api/v1/time"))
            val serverTime = response.optLong("serverTime", 0L)
            val after = System.currentTimeMillis()
            if (serverTime <= 0L) throw IllegalStateException("زمان سرور توبیت معتبر نیست.")
            serverOffsetMs = serverTime - ((before + after) / 2L)
            serverTimeSyncLocalMs = after
        }
        return System.currentTimeMillis() + (serverOffsetMs ?: 0L)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "AURUM-X-Android")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Toobit HTTP $code: $body")
            return body
        } finally {
            c.disconnect()
        }
    }

    fun contracts(exchange: String): List<String> {
        require(exchange == "Toobit") { "فقط صرافی توبیت پشتیبانی می‌شود." }
        val root = JSONObject(get("https://api.toobit.com/api/v1/exchangeInfo"))
        val rows = root.optJSONArray("contracts") ?: JSONArray()
        val out = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val rawSymbol = item.optString("symbol").trim()
            // Toobit exchangeInfo lists USDT-M contracts as BTC-SWAP,
            // while quote endpoints use the canonical BTC-SWAP-USDT form.
            val symbol = when {
                rawSymbol.endsWith("-SWAP-USDT") -> rawSymbol
                rawSymbol.endsWith("-SWAP") -> "$rawSymbol-USDT"
                else -> ""
            }
            if (item.optString("status") == "TRADING" && symbol.isNotEmpty()) {
                out.add(symbol)
            }
        }
        return out.distinct().sorted()
    }

    fun candles(exchange: String, symbol: String, interval: String): List<MarketCandle> {
        require(exchange == "Toobit") { "فقط صرافی توبیت پشتیبانی می‌شود." }
        require(interval == "5m" || interval == "15m" || interval == "30m") {
            "تایم‌فریم نامعتبر است: $interval"
        }
        require(symbol.endsWith("-SWAP-USDT")) { "نماد قرارداد توبیت نامعتبر است: $symbol" }

        val now = exchangeTimeMs()
        val duration = when (interval) {
            "5m" -> 300_000L
            "15m" -> 900_000L
            else -> 1_800_000L
        }
        val url = "https://api.toobit.com/quote/v1/markPrice/klines" +
            "?symbol=$symbol&interval=$interval&from=${now - duration * 260}&to=$now&limit=260"
        val root = JSONObject(get(url))
        val rows = root.optJSONArray("data") ?: JSONArray()
        val result = mutableListOf<MarketCandle>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            try {
                val candleTime = row.optLong("time", row.optLong("curId", 0L))
                if (candleTime <= 0L || candleTime + duration > now) continue
                val open = row.optString("open").toDouble()
                val high = row.optString("high").toDouble()
                val low = row.optString("low").toDouble()
                val close = row.optString("close").toDouble()
                val volume = row.optString("volume").toDoubleOrNull() ?: 0.0
                if (open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0) {
                    result.add(MarketCandle(open, high, low, close, volume, candleTime))
                }
            } catch (_: Exception) {
                // Ignore malformed rows without discarding otherwise valid candles.
            }
        }
        if (result.size < 60) {
            throw IllegalStateException("توبیت: تعداد کندل معتبر $interval کافی نیست (${result.size}).")
        }
        return result.sortedBy { it.time }
    }

    /** Latest traded futures price; used for 60-second risk checks, not candle-close analysis. */
    fun lastPrice(exchange: String, symbol: String): Double {
        require(exchange == "Toobit") { "فقط صرافی توبیت پشتیبانی می‌شود." }
        require(symbol.endsWith("-SWAP-USDT")) { "نماد قرارداد توبیت نامعتبر است: $symbol" }
        val root = JSONArray(get("https://api.toobit.com/quote/v1/contract/ticker/price?symbol=$symbol"))
        for (i in 0 until root.length()) {
            val row = root.optJSONObject(i) ?: continue
            if (row.optString("s") == symbol) {
                val price = row.optString("p").toDoubleOrNull() ?: 0.0
                if (price > 0.0) return price
            }
        }
        throw IllegalStateException("قیمت لحظه‌ای $symbol از توبیت دریافت نشد.")
    }
}
