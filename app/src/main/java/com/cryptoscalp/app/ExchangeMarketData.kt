package com.cryptoscalp.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public USDT perpetual market-data adapters. Symbols are stored internally as BASE-SWAP-USDT. */
object ExchangeMarketData {
    val supported = listOf("Toobit", "Binance", "Bybit", "OKX", "Gate.io", "Bitget", "BingX", "CoinEx")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "AURUM-X-Android")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $body")
            return body
        } finally { c.disconnect() }
    }

    private fun base(symbol: String) = symbol.removeSuffix("-SWAP-USDT").removeSuffix("USDT").removeSuffix("_USDT")
    private fun binanceInterval(i: String) = i
    private fun bybitInterval(i: String) = when(i) { "1m"->"1"; "5m"->"5"; "15m"->"15"; else->"60" }
    private fun okxBar(i: String) = when(i) { "1m"->"1m"; "5m"->"5m"; "15m"->"15m"; else->"1H" }
    private fun bitgetGranularity(i: String) = when(i) { "1m"->"1min"; "5m"->"5min"; "15m"->"15min"; else->"1H" }

    fun contracts(exchange: String): List<String> {
        val out = mutableListOf<String>()
        when(exchange) {
            "Toobit" -> {
                val a=JSONObject(get("https://api.toobit.com/api/v1/exchangeInfo")).optJSONArray("contracts") ?: JSONArray()
                for(i in 0 until a.length()) { val o=a.getJSONObject(i); val sym=o.optString("symbol")
                    if(o.optString("status")=="TRADING" && sym.endsWith("-SWAP-USDT")) out.add(sym) }
            }
            "Binance" -> {
                val a=JSONObject(get("https://fapi.binance.com/fapi/v1/exchangeInfo")).optJSONArray("symbols") ?: JSONArray()
                for(i in 0 until a.length()) { val o=a.getJSONObject(i); if(o.optString("status")=="TRADING" && o.optString("contractType")=="PERPETUAL" && o.optString("quoteAsset")=="USDT") out.add("${o.optString("baseAsset")}-SWAP-USDT") }
            }
            "Bybit" -> {
                val a=JSONObject(get("https://api.bybit.com/v5/market/instruments-info?category=linear&limit=1000")).optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
                for(i in 0 until a.length()) { val o=a.getJSONObject(i); if(o.optString("status")=="Trading" && o.optString("settleCoin")=="USDT" && o.optString("symbol").endsWith("USDT")) out.add("${o.optString("baseCoin")}-SWAP-USDT") }
            }
            "OKX" -> {
                val a=JSONObject(get("https://www.okx.com/api/v5/public/instruments?instType=SWAP")).optJSONArray("data") ?: JSONArray()
                for(i in 0 until a.length()) { val o=a.getJSONObject(i); if(o.optString("state")=="live" && o.optString("settleCcy")=="USDT") out.add("${o.optString("ctValCcy")}-SWAP-USDT") }
            }
            "Gate.io" -> {
                val a=JSONArray(get("https://api.gateio.ws/api/v4/futures/usdt/contracts"))
                for(i in 0 until a.length()) { val sym=a.getJSONObject(i).optString("name"); if(sym.endsWith("_USDT")) out.add("${sym.removeSuffix("_USDT")}-SWAP-USDT") }
            }
            "Bitget" -> {
                val a=JSONObject(get("https://api.bitget.com/api/v2/mix/market/contracts?productType=USDT-FUTURES")).optJSONArray("data") ?: JSONArray()
                for(i in 0 until a.length()) { val o=a.getJSONObject(i); if(o.optString("symbolStatus")=="normal") out.add("${o.optString("baseCoin")}-SWAP-USDT") }
            }
            "BingX" -> {
                val a=JSONObject(get("https://open-api.bingx.com/openApi/swap/v2/quote/contracts")).optJSONArray("data") ?: JSONArray()
                for(i in 0 until a.length()) { val sym=a.getJSONObject(i).optString("symbol"); if(sym.endsWith("-USDT")) out.add("${sym.removeSuffix("-USDT")}-SWAP-USDT") }
            }
            "CoinEx" -> {
                val a=JSONObject(get("https://api.coinex.com/v2/futures/market")).optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
                for(i in 0 until a.length()) { val o=a.getJSONObject(i); val sym=o.optString("market"); if(sym.endsWith("USDT")) out.add(sym.removeSuffix("USDT")+"-SWAP-USDT") }
            }
        }
        return out.distinct().sorted()
    }

    fun candles(exchange: String, symbol: String, interval: String): List<MarketCandle> {
        val b=base(symbol)
        val url=when(exchange) {
            "Toobit" -> { val now=System.currentTimeMillis(); val d=when(interval){"1m"->60000L;"5m"->300000L;"15m"->900000L;else->3600000L}; "https://api.toobit.com/quote/v1/markPrice/klines?symbol=$symbol&interval=$interval&from=${now-d*260}&to=$now&limit=260" }
            "Binance" -> "https://fapi.binance.com/fapi/v1/klines?symbol=${b}USDT&interval=${binanceInterval(interval)}&limit=260"
            "Bybit" -> "https://api.bybit.com/v5/market/kline?category=linear&symbol=${b}USDT&interval=${bybitInterval(interval)}&limit=260"
            "OKX" -> "https://www.okx.com/api/v5/market/candles?instId=${b}-USDT-SWAP&bar=${okxBar(interval)}&limit=300"
            "Gate.io" -> "https://api.gateio.ws/api/v4/futures/usdt/candlesticks?contract=${b}_USDT&interval=$interval&limit=260"
            "Bitget" -> "https://api.bitget.com/api/v2/mix/market/candles?productType=USDT-FUTURES&symbol=${b}USDT&granularity=${bitgetGranularity(interval)}&limit=260"
            "BingX" -> "https://open-api.bingx.com/openApi/swap/v3/quote/klines?symbol=${b}-USDT&interval=$interval&limit=260"
            "CoinEx" -> "https://api.coinex.com/v2/futures/kline?market="+b+"USDT&period="+(when(interval){"1m"->"1min";"5m"->"5min";"15m"->"15min";else->"1hour"})+"&limit=260"
            else -> throw IllegalArgumentException("صرافی پشتیبانی نمی‌شود: $exchange")
        }
        val root=JSONObjectOrArray(get(url))
        val rows: JSONArray = when(exchange) {
            "Toobit" -> (root as JSONObject).optJSONArray("data") ?: JSONArray()
            "Binance","OKX","Gate.io" -> root as JSONArray
            "Bybit" -> (root as JSONObject).optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
            "Bitget" -> (root as JSONObject).optJSONArray("data") ?: JSONArray()
            "BingX" -> (root as JSONObject).optJSONArray("data") ?: JSONArray()
            "CoinEx" -> (root as JSONObject).optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val result=mutableListOf<MarketCandle>()
        for(i in 0 until rows.length()) {
            try {
                val row=rows.get(i)
                val values=when(exchange) {
                    "Toobit" -> { val o=row as JSONObject; listOf(o.optString("open"),o.optString("high"),o.optString("low"),o.optString("close"),o.optString("volume")) }
                    "Gate.io" -> { val o=row as JSONObject; listOf(o.optString("o"),o.optString("h"),o.optString("l"),o.optString("c"),o.optString("v")) }
                    "Binance" -> { val a=row as JSONArray; listOf(a.optString(1),a.optString(2),a.optString(3),a.optString(4),a.optString(5)) }
                    "Bybit","OKX","Bitget" -> { val a=row as JSONArray; listOf(a.optString(1),a.optString(2),a.optString(3),a.optString(4),a.optString(5)) }
                    "BingX" -> { val o=row as JSONObject; listOf(o.optString("open"),o.optString("high"),o.optString("low"),o.optString("close"),o.optString("volume")) }
                    "CoinEx" -> { val o=row as JSONObject; listOf(o.optString("open"),o.optString("high"),o.optString("low"),o.optString("close"),o.optString("volume")) }
                    else -> emptyList()
                }
                val o=values[0].toDouble(); val h=values[1].toDouble(); val l=values[2].toDouble(); val c=values[3].toDouble(); val v=values[4].toDoubleOrNull()?:0.0
                if(o>0&&h>0&&l>0&&c>0) result.add(MarketCandle(o,h,l,c,v))
            } catch(_:Exception) {}
        }
        if(result.size<60) throw IllegalStateException("$exchange: کندل کافی دریافت نشد (${result.size})")
        // Bybit, OKX and Bitget return newest-first candles; the strategy expects oldest-first.
        if(exchange=="Bybit" || exchange=="OKX" || exchange=="Bitget" || exchange=="BingX") result.reverse()
        return result
    }

    fun lastPrice(exchange:String,symbol:String):Double = candles(exchange,symbol,"1m").last().close

    private fun JSONObjectOrArray(body:String):Any {
        val t=body.trim()
        return if(t.startsWith("[")) JSONArray(t) else JSONObject(t)
    }
}
