"""Walk-forward, read-only backtesting for the AURUM X reference strategy.

Input timeframe arrays must be aligned by index and contain the same number of
oldest-to-newest closed candles. Entries use the NEXT 5m candle open to avoid
same-candle look-ahead; exits use the close after the requested holding horizon.
No exchange orders are ever sent.
"""
from __future__ import annotations

from typing import Any, Dict
from signal_engine import REQUIRED_TFS, analyze_market


def run_backtest(timeframes: Dict[str, list], holding_bars: int = 3,
                 fee_bps: float = 8.0) -> Dict[str, Any]:
    if holding_bars < 1:
        raise ValueError("holding_bars must be at least 1")
    if fee_bps < 0:
        raise ValueError("fee_bps cannot be negative")
    missing = [tf for tf in REQUIRED_TFS if tf not in timeframes]
    if missing:
        raise ValueError("Missing timeframes: " + ", ".join(missing))
    lengths = [len(timeframes[tf]) for tf in REQUIRED_TFS]
    if len(set(lengths)) != 1:
        raise ValueError("Timeframe arrays must be index-aligned and equal length")
    candles = timeframes["5m"]
    n = len(candles)
    if n < 60 + holding_bars + 1:
        raise ValueError("Not enough aligned candles for warm-up and holding horizon")

    trades = []
    i = 59
    fee = 2.0 * fee_bps / 10000.0
    while i + holding_bars + 1 < n:
        window = {tf: timeframes[tf][:i + 1] for tf in REQUIRED_TFS}
        result = analyze_market(window)
        side = result.get("signal")
        if side not in ("LONG", "SHORT"):
            i += 1
            continue
        entry_index = i + 1
        exit_index = entry_index + holding_bars
        entry = float(candles[entry_index]["open"])
        exit_price = float(candles[exit_index]["close"])
        if entry <= 0:
            i += 1
            continue
        gross = (exit_price - entry) / entry if side == "LONG" else (entry - exit_price) / entry
        net = gross - fee
        trades.append({
            "side": side, "signal_index": i, "entry_index": entry_index,
            "exit_index": exit_index, "entry": entry, "exit": exit_price,
            "gross_return_pct": round(gross * 100.0, 5),
            "net_return_pct": round(net * 100.0, 5),
        })
        i = exit_index + 1  # one position at a time; no overlapping test trades

    wins = sum(t["net_return_pct"] > 0 for t in trades)
    compounded = 1.0
    for trade in trades:
        compounded *= 1.0 + trade["net_return_pct"] / 100.0
    gains = sum(t["net_return_pct"] for t in trades if t["net_return_pct"] > 0)
    losses = -sum(t["net_return_pct"] for t in trades if t["net_return_pct"] < 0)
    return {
        "trades": trades,
        "trade_count": len(trades),
        "win_rate_pct": round(100.0 * wins / len(trades), 2) if trades else 0.0,
        "net_return_pct": round((compounded - 1.0) * 100.0, 5),
        "profit_factor": round(gains / losses, 4) if losses else (None if gains == 0 else "infinite"),
        "holding_bars": holding_bars,
        "round_trip_fee_bps": round(2.0 * fee_bps, 3),
        "note": "Historical simulation only; fees are estimated, and slippage/funding/liquidation are not modeled.",
    }
