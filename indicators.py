

import pandas as pd
import numpy as np


def calculate_indicators(data):
    df = pd.DataFrame(data).copy()

    if len(df) < 50:
        raise ValueError("At least 50 candles are required")

    close = pd.to_numeric(df["close"])
    high = pd.to_numeric(df["high"])
    low = pd.to_numeric(df["low"])

    # EMA
    df["EMA9"] = close.ewm(span=9, adjust=False).mean()
    df["EMA21"] = close.ewm(span=21, adjust=False).mean()
    df["EMA50"] = close.ewm(span=50, adjust=False).mean()

    # RSI
    delta = close.diff()
    gain = delta.clip(lower=0)
    loss = -delta.clip(upper=0)

    avg_gain = gain.ewm(
        alpha=1/14, adjust=False, min_periods=14
    ).mean()

    avg_loss = loss.ewm(
        alpha=1/14, adjust=False, min_periods=14
    ).mean()

    rs = avg_gain / avg_loss.replace(0, np.nan)
    df["RSI"] = 100 - (100 / (1 + rs))
    df.loc[avg_loss == 0, "RSI"] = 100

    # MACD
    ema12 = close.ewm(span=12, adjust=False).mean()
    ema26 = close.ewm(span=26, adjust=False).mean()

    df["MACD"] = ema12 - ema26
    df["MACD_SIGNAL"] = df["MACD"].ewm(
        span=9, adjust=False
    ).mean()

    df["MACD_HIST"] = (
        df["MACD"] - df["MACD_SIGNAL"]
    )

    # Bollinger Bands
    middle = close.rolling(20).mean()
    std = close.rolling(20).std()

    df["BB_MIDDLE"] = middle
    df["BB_UPPER"] = middle + (2 * std)
    df["BB_LOWER"] = middle - (2 * std)

    # ATR
    previous_close = close.shift(1)

    tr = pd.concat([
        high - low,
        (high - previous_close).abs(),
        (low - previous_close).abs()
    ], axis=1).max(axis=1)

    df["ATR"] = tr.ewm(
        alpha=1/14, adjust=False
    ).mean()

    return df


def detect_trend(df):
    last = df.iloc[-1]

    if last["EMA9"] > last["EMA21"] > last["EMA50"]:
        return "BULLISH"

    if last["EMA9"] < last["EMA21"] < last["EMA50"]:
        return "BEARISH"

    return "SIDEWAYS"


def generate_signal(df):
    last = df.iloc[-1]
    trend = detect_trend(df)

    if (
        trend == "BULLISH"
        and last["RSI"] > 50
        and last["MACD"] > last["MACD_SIGNAL"]
    ):
        return "LONG"

    if (
        trend == "BEARISH"
        and last["RSI"] < 50
        and last["MACD"] < last["MACD_SIGNAL"]
    ):
        return "SHORT"

    return "WAIT"
