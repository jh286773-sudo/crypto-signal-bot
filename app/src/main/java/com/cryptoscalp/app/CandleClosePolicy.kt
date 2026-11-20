package com.cryptoscalp.app

/** A Toobit kline timestamp is its open time; the candle is closed at open + interval. */
object CandleClosePolicy {
    fun isClosed(openTimeMs: Long, durationMs: Long, asOfMs: Long): Boolean =
        openTimeMs > 0L && durationMs > 0L && asOfMs >= openTimeMs + durationMs
}
