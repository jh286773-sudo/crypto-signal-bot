
from indicators import calculate_indicators, generate_signal
from smart_money import analyze_smc


def analyze_market(candles):
    if len(candles) < 60:
        return {"signal": "WAIT", "reason": "Not enough candles"}

    df = calculate_indicators(candles)

    trend = generate_signal(df)
    smc = analyze_smc(df)

    structure = smc["market_structure"]
    sweeps = smc["liquidity_sweeps"]

    score = 0
    reasons = []

    if trend == "LONG":
        score += 2
        reasons.append("Bullish indicators")

    elif trend == "SHORT":
        score -= 2
        reasons.append("Bearish indicators")

    if structure == "BULLISH":
        score += 2
        reasons.append("Bullish market structure")

    elif structure == "BEARISH":
        score -= 2
        reasons.append("Bearish market structure")

    if "BULLISH_LIQUIDITY_SWEEP" in sweeps:
        score += 2
        reasons.append("Bullish liquidity sweep")

    if "BEARISH_LIQUIDITY_SWEEP" in sweeps:
        score -= 2
        reasons.append("Bearish liquidity sweep")

    if score >= 3:
        signal = "LONG"

    elif score <= -3:
        signal = "SHORT"

    else:
        signal = "WAIT"

    last = df.iloc[-1]

    return {
        "signal": signal,
        "score": score,
        "trend": trend,
        "market_structure": structure,
        "liquidity_sweeps": sweeps,
        "price": float(last["close"]),
        "rsi": float(last["RSI"]),
        "atr": float(last["ATR"]),
        "reasons": reasons
    }
