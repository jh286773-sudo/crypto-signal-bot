"""Walk-forward, read-only backtesting for the AURUM X reference strategy.

Aligned oldest-to-newest closed candles are required. Entries use the next 5m
open; exits use the close after the holding horizon. Costs include configurable
round-trip fees, per-side slippage, and a time-scaled funding estimate.
No exchange orders are ever sent.
"""
from __future__ import annotations

from typing import Any, Dict
from signal_engine import REQUIRED_TFS, analyze_market


def run_backtest(timeframes: Dict[str, list], holding_bars: int = 3,
                 fee_bps: float = 8.0, slippage_bps: float = 2.0,
                 funding_bps_per_8h: float = 0.0) -> Dict[str, Any]:
    if holding_bars < 1:
        raise ValueError("holding_bars must be at least 1")
    if fee_bps < 0 or slippage_bps < 0 or funding_bps_per_8h < 0:
        raise ValueError("fee, slippage and funding assumptions cannot be negative")
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
    funding_cost = funding_bps_per_8h * (holding_bars * 5.0 / 480.0)
    total_cost = (2.0 * fee_bps + 2.0 * slippage_bps + funding_cost) / 10000.0
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
        net = gross - total_cost
        trades.append({
            "side": side, "signal_index": i, "entry_index": entry_index,
            "exit_index": exit_index, "entry": entry, "exit": exit_price,
            "gross_return_pct": round(gross * 100.0, 5),
            "net_return_pct": round(net * 100.0, 5),
        })
        i = exit_index + 1  # one position at a time; no overlapping test trades

    wins = sum(t["net_return_pct"] > 0 for t in trades)
    compounded = 1.0
    peak = 1.0
    max_drawdown = 0.0
    for trade in trades:
        compounded *= 1.0 + trade["net_return_pct"] / 100.0
        peak = max(peak, compounded)
        if peak > 0:
            max_drawdown = max(max_drawdown, (peak - compounded) / peak)
    returns = [t["net_return_pct"] for t in trades]
    gains = sum(x for x in returns if x > 0)
    losses = -sum(x for x in returns if x < 0)
    return {
        "trades": trades,
        "trade_count": len(trades),
        "win_rate_pct": round(100.0 * wins / len(trades), 2) if trades else 0.0,
        "net_return_pct": round((compounded - 1.0) * 100.0, 5),
        "profit_factor": round(gains / losses, 4) if losses else (None if gains == 0 else "infinite"),
        "max_drawdown_pct": round(max_drawdown * 100.0, 5),
        "average_trade_pct": round(sum(returns) / len(returns), 5) if returns else 0.0,
        "expectancy_pct": round(sum(returns) / len(returns), 5) if returns else 0.0,
        "holding_bars": holding_bars,
        "round_trip_fee_bps": round(2.0 * fee_bps, 3),
        "round_trip_slippage_bps": round(2.0 * slippage_bps, 3),
        "estimated_funding_bps": round(funding_cost, 4),
        "note": "Historical estimate only. Supply exchange-specific fees, slippage and funding; liquidation and intrabar stop/target execution are not modeled.",
    }
