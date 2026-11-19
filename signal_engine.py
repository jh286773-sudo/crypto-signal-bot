from indicators import calculate_indicators, detect_trend, generate_signal
from smart_money import analyze_smc


def analyze_market(multi_timeframe):
    """Compare 5m entry, 15m setup direction, and 30m trend confirmation."""
    required = ("5m", "15m", "30m")
    if not isinstance(multi_timeframe, dict):
        return {"signal": "WAIT", "reason": "Expected multi-timeframe candle data"}

    for tf in required:
        if tf not in multi_timeframe:
            return {"signal": "WAIT", "reason": f"Missing {tf} candles"}
        if len(multi_timeframe[tf]) < 60:
            return {"signal": "WAIT", "reason": f"Not enough {tf} candles"}

    data = {tf: calculate_indicators(multi_timeframe[tf]) for tf in required}
    trends = {tf: detect_trend(data[tf]) for tf in required}
    signals = {tf: generate_signal(data[tf]) for tf in required}

    trend_30m = trends["30m"]
    trend_15m = trends["15m"]
    reasons = []

    # 15m defines the setup direction; 1h disagreement reduces confidence
    # rather than blocking every otherwise valid scalp.
    if trend_15m == "BULLISH":
        direction = "LONG"
        reasons.append("15m trend is bullish")
    elif trend_15m == "BEARISH":
        direction = "SHORT"
        reasons.append("15m trend is bearish")
    else:
        return {
            "signal": "WAIT",
            "reason": "15m trend is sideways",
            "timeframes": trends,
        }

    if signals["5m"] != direction:
        return {
            "signal": "WAIT",
            "reason": "5m entry signal is not confirmed",
            "timeframes": trends,
            "entry_signals": signals,
        }

    score = 3
    reasons.append("5m scalp entry confirmed")
    if trend_30m == trend_15m:
        score += 1
        reasons.append("30m trend confirms 15m direction")
    else:
        score -= 1
        reasons.append("30m trend does not confirm; confidence reduced")

    df = data["5m"]
    smc = analyze_smc(df)
    structure = smc["market_structure"]
    sweeps = smc["liquidity_sweeps"]
    order_blocks = smc["order_blocks"]

    if direction == "LONG":
        if structure == "BULLISH":
            score += 2
            reasons.append("Bullish market structure")
        elif structure == "BEARISH":
            score -= 2
            reasons.append("Bearish market structure warning")
        if "BULLISH_LIQUIDITY_SWEEP" in sweeps:
            score += 1
            reasons.append("Bullish liquidity sweep")
        if any(block["type"] == "BULLISH" for block in order_blocks):
            score += 1
            reasons.append("Bullish order block")
    else:
        if structure == "BEARISH":
            score += 2
            reasons.append("Bearish market structure")
        elif structure == "BULLISH":
            score -= 2
            reasons.append("Bullish market structure warning")
        if "BEARISH_LIQUIDITY_SWEEP" in sweeps:
            score += 1
            reasons.append("Bearish liquidity sweep")
        if any(block["type"] == "BEARISH" for block in order_blocks):
            score += 1
            reasons.append("Bearish order block")

    # Keep a minimum confidence threshold; never force a trade.
    signal = direction if score >= 2 else "WAIT"
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
        "reasons": reasons,
    }
