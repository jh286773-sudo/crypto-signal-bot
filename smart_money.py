
import pandas as pd


def detect_swing_points(df, window=3):
    highs = df["high"]
    lows = df["low"]

    swing_highs = []
    swing_lows = []

    for i in range(window, len(df) - window):
        if highs.iloc[i] == highs.iloc[
            i-window:i+window+1
        ].max():
            swing_highs.append({
                "index": i,
                "price": float(highs.iloc[i])
            })

        if lows.iloc[i] == lows.iloc[
            i-window:i+window+1
        ].min():
            swing_lows.append({
                "index": i,
                "price": float(lows.iloc[i])
            })

    return swing_highs, swing_lows


def detect_market_structure(df):
    highs, lows = detect_swing_points(df)

    if len(highs) < 2 or len(lows) < 2:
        return "UNKNOWN"

    higher_high = highs[-1]["price"] > highs[-2]["price"]
    higher_low = lows[-1]["price"] > lows[-2]["price"]

    lower_high = highs[-1]["price"] < highs[-2]["price"]
    lower_low = lows[-1]["price"] < lows[-2]["price"]

    if higher_high and higher_low:
        return "BULLISH"

    if lower_high and lower_low:
        return "BEARISH"

    return "RANGE"


def detect_liquidity_sweeps(df, window=20):
    if len(df) < window + 2:
        return []

    recent = df.iloc[-window-1:-1]
    last = df.iloc[-1]

    signals = []

    if (
        last["high"] > recent["high"].max()
        and last["close"] < recent["high"].max()
    ):
        signals.append("BEARISH_LIQUIDITY_SWEEP")

    if (
        last["low"] < recent["low"].min()
        and last["close"] > recent["low"].min()
    ):
        signals.append("BULLISH_LIQUIDITY_SWEEP")

    return signals


def detect_order_blocks(df, lookback=20):
    if len(df) < lookback + 2:
        return []

    blocks = []

    for i in range(-lookback, -1):
        candle = df.iloc[i]
        next_candle = df.iloc[i+1]

        if (
            candle["close"] < candle["open"]
            and next_candle["close"] > candle["high"]
        ):
            blocks.append({
                "type": "BULLISH",
                "low": float(candle["low"]),
                "high": float(candle["high"])
            })

        elif (
            candle["close"] > candle["open"]
            and next_candle["close"] < candle["low"]
        ):
            blocks.append({
                "type": "BEARISH",
                "low": float(candle["low"]),
                "high": float(candle["high"])
            })

    return blocks


def analyze_smc(df):
    return {
        "market_structure": detect_market_structure(df),
        "liquidity_sweeps": detect_liquidity_sweeps(df),
        "order_blocks": detect_order_blocks(df)
    }

