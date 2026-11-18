
from indicators import (
    calculate_indicators,
    detect_trend,
    generate_signal
)
from smart_money import analyze_smc


def analyze_market(multi_timeframe):
    """
    Analyze 5m, 15m and 1h candles.
    1h and 15m confirm direction.
    5m is used for scalp entry.
    """

    required = ("5m", "15m", "1h")

    if not isinstance(multi_timeframe, dict):
        return {
            "signal": "WAIT",
            "reason": "Expected multi-timeframe candle data"
        }

    for tf in required:
        if tf not in multi_timeframe:
            return {
                "signal": "WAIT",
                "reason": f"Missing {tf} candles"
            }

        if len(multi_timeframe[tf]) < 60:
            return {
                "signal": "WAIT",
                "reason": f"Not enough {tf} candles"
            }

    # Calculate indicators for each timeframe
    data = {
        tf: calculate_indicators(multi_timeframe[tf])
        for tf in required
    }

    trends = {
        tf: detect_trend(data[tf])
        for tf in required
    }

    signals = {
        tf: generate_signal(data[tf])
        for tf in required
    }

    trend_1h = trends["1h"]
    trend_15m = trends["15m"]
    trend_5m = trends["5m"]

    reasons = []

    # Higher timeframes must agree
    if trend_1h == "BULLISH" and trend_15m == "BULLISH":
        direction = "LONG"
        reasons.append("1h and 15m trends are bullish")

    elif trend_1h == "BEARISH" and trend_15m == "BEARISH":
        direction = "SHORT"
        reasons.append("1h and 15m trends are bearish")

    else:
        return {
            "signal": "WAIT",
            "reason": "1h and 15m trends are not aligned",
            "timeframes": trends
        }

    # 5m scalp entry confirmation
    if signals["5m"] != direction:
        return {
            "signal": "WAIT",
            "reason": "5m entry signal is not confirmed",
            "timeframes": trends,
            "entry_signals": signals
        }

    # Smart Money analysis on 5m
    df = data["5m"]
    smc = analyze_smc(df)

    structure = smc["market_structure"]
    sweeps = smc["liquidity_sweeps"]
    order_blocks = smc["order_blocks"]

    score = 3
    reasons.append("5m entry signal confirmed")

    if direction == "LONG":
        if structure == "BULLISH":
            score += 2
            reasons.append("Bullish market structure")

        if "BULLISH_LIQUIDITY_SWEEP" in sweeps:
            score += 2
            reasons.append("Bullish liquidity sweep")

        if any(block["type"] == "BULLISH"
               for block in order_blocks):
            score += 1
            reasons.append("Bullish order block")

        if structure == "BEARISH":
            score -= 2
            reasons.append("Bearish market structure warning")

    else:
        if structure == "BEARISH":
            score += 2
            reasons.append("Bearish market structure")

        if "BEARISH_LIQUIDITY_SWEEP" in sweeps:
            score += 2
            reasons.append("Bearish liquidity sweep")

        if any(block["type"] == "BEARISH"
               for block in order_blocks):
            score += 1
            reasons.append("Bearish order block")

        if structure == "BULLISH":
            score -= 2
            reasons.append("Bullish market structure warning")

    # Reject weak or conflicting setups
    if score < 3:
        signal = "WAIT"
    else:
        signal = direction

    last = df.iloc[-1]

    return {
        "signal": signal,
        "score": score,
        "direction": direction,
        "timeframes": trends,
        "entry_signals": signals,
        "market_structure": structure,
        "liquidity_sweeps": sweeps,
        "order_blocks": order_blocks,
        "price": float(last["close"]),
        "rsi": float(last["RSI"]),
        "atr": float(last["ATR"]),
        "reasons": reasons
    }
