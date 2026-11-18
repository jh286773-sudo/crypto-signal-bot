
from typing import Dict, List, Any
from smc import analyze_smc


def ema(values, period):
    if len(values) < period:
        return None
    alpha = 2 / (period + 1)
    result = sum(values[:period]) / period
    for value in values[period:]:
        result = value * alpha + result * (1 - alpha)
    return result


def rsi(values, period=14):
    if len(values) <= period:
        return None

    gains = []
    losses = []

    for i in range(1, len(values)):
        change = values[i] - values[i - 1]
        gains.append(max(change, 0))
        losses.append(max(-change, 0))

    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period

    for i in range(period, len(gains)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period

    if avg_loss == 0:
        return 100

    return 100 - (100 / (1 + avg_gain / avg_loss))


def macd(values):
    fast = ema(values, 12)
    slow = ema(values, 26)

    if fast is None or slow is None:
        return None

    return fast - slow


def indicators(candles):
    closes = [float(c["close"]) for c in candles]
    highs = [float(c["high"]) for c in candles]
    lows = [float(c["low"]) for c in candles]

    if len(closes) < 50:
        return None

    rsi_value = rsi(closes)
    ema_fast = ema(closes, 9)
    ema_slow = ema(closes, 21)
    ema_trend = ema(closes, 50)
    macd_value = macd(closes)

    middle = sum(closes[-20:]) / 20
    variance = sum((x - middle) ** 2 for x in closes[-20:]) / 20
    deviation = variance ** 0.5

    atr_values = []
    for i in range(1, len(candles)):
        tr = max(
            highs[i] - lows[i],
            abs(highs[i] - closes[i - 1]),
            abs(lows[i] - closes[i - 1])
        )
        atr_values.append(tr)

    atr = sum(atr_values[-14:]) / 14
    price = closes[-1]

    return {
        "price": price,
        "rsi": rsi_value,
        "ema9": ema_fast,
        "ema21": ema_slow,
        "ema50": ema_trend,
        "macd": macd_value,
        "bb_upper": middle + 2 * deviation,
        "bb_lower": middle - 2 * deviation,
        "atr": atr
    }


def analyze_timeframe(candles):
    ind = indicators(candles)

    if ind is None:
        return {"signal": "WAIT", "reason": "Not enough candles"}

    score = 0

    if ind["ema9"] > ind["ema21"] > ind["ema50"]:
        score += 2
    elif ind["ema9"] < ind["ema21"] < ind["ema50"]:
        score -= 2

    if ind["macd"] > 0:
        score += 1
    elif ind["macd"] < 0:
        score -= 1

    if ind["rsi"] > 55:
        score += 1
    elif ind["rsi"] < 45:
        score -= 1

    if ind["price"] > ind["ema50"]:
        score += 1
    elif ind["price"] < ind["ema50"]:
        score -= 1

    if score >= 3:
        signal = "LONG"
    elif score <= -3:
        signal = "SHORT"
    else:
        signal = "WAIT"

    return {
        "signal": signal,
        "score": score,
        "indicators": ind
    }


def analyze_market(data):
    """
    data = {
        "1m": candles,
        "5m": candles,
        "15m": candles,
        "1h": candles
    }
    """

    required = ["1m", "5m", "15m", "1h"]

    if not all(tf in data for tf in required):
        return {"error": "Missing timeframe data"}

    results = {
        tf: analyze_timeframe(data[tf])
        for tf in required
    }

    trend = results["15m"]["signal"]
    hourly = results["1h"]["signal"]
    scalp = results["1m"]["signal"]
    five_min = results["5m"]["signal"]

    direction = "WAIT"

    if trend == "LONG" and hourly == "LONG":
        if scalp == "LONG" or five_min == "LONG":
            direction = "LONG"

    elif trend == "SHORT" and hourly == "SHORT":
        if scalp == "SHORT" or five_min == "SHORT":
            direction = "SHORT"

    # Smart Money analysis on 5-minute candles
    smc = analyze_smc(data["5m"])

    price = results["5m"]["indicators"]["price"]
    atr = results["5m"]["indicators"]["atr"]

    entry = price
    stop_loss = None
    targets = []

    if direction == "LONG":
        stop_loss = entry - 1.5 * atr
        targets = [
            entry + atr,
            entry + 2 * atr,
            entry + 3 * atr
        ]

    elif direction == "SHORT":
        stop_loss = entry + 1.5 * atr
        targets = [
            entry - atr,
            entry - 2 * atr,
            entry - 3 * atr
        ]

    return {
        "signal": direction,
        "entry": entry if direction != "WAIT" else None,
        "stop_loss": stop_loss,
        "take_profits": targets,
        "timeframes": results,
        "smart_money": smc,
        "trade_enabled": False
    }

