"""Walk-forward, read-only backtesting for AURUM X.

Signals use only closed candles. Entry is the next 5m open. If the strategy
provides ATR/structure-derived stop and targets, subsequent OHLC bars are
checked for intrabar exits; ambiguous stop/target hits in one bar resolve to
the stop first. Fees, per-side slippage and time-scaled funding are deducted.
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
    while i + holding_bars + 1 < n:
        window = {tf: timeframes[tf][:i + 1] for tf in REQUIRED_TFS}
        result = analyze_market(window)
        side = result.get("signal")
        if side not in ("LONG", "SHORT"):
            i += 1
            continue

        entry_index = i + 1
        entry = float(candles[entry_index]["open"])
        if entry <= 0:
            i += 1
            continue
        signal_close = float(candles[i]["close"])
        stop_signal = result.get("stop_loss")
        targets_signal = result.get("take_profits") or []
        has_brackets = stop_signal is not None and len(targets_signal) >= 2
        stop = target = None
        if has_brackets:
            stop_distance = abs(signal_close - float(stop_signal))
            target_distance = abs(float(targets_signal[1]) - signal_close)
            if stop_distance > 0 and target_distance > 0:
                stop = entry - stop_distance if side == "LONG" else entry + stop_distance
                target = entry + target_distance if side == "LONG" else entry - target_distance

        last_index = min(entry_index + holding_bars, n - 1)
        exit_index = last_index
        exit_price = float(candles[last_index]["close"])
        exit_reason = "time"
        held = 0
        if stop is not None and target is not None:
            for j in range(entry_index, last_index + 1):
                bar = candles[j]
                o, h, low = float(bar["open"]), float(bar["high"]), float(bar["low"])
                held += 1
                if side == "LONG":
                    hit_stop, hit_target = low <= stop, h >= target
                    if hit_stop and hit_target:
                        exit_price, exit_reason = min(o, stop), "stop_ambiguous_bar"
                    elif hit_stop:
                        exit_price, exit_reason = min(o, stop), "stop"
                    elif hit_target:
                        exit_price, exit_reason = max(o, target), "target_2"
                    else:
                        continue
                else:
                    hit_stop, hit_target = h >= stop, low <= target
                    if hit_stop and hit_target:
                        exit_price, exit_reason = max(o, stop), "stop_ambiguous_bar"
                    elif hit_stop:
                        exit_price, exit_reason = max(o, stop), "stop"
                    elif hit_target:
                        exit_price, exit_reason = min(o, target), "target_2"
                    else:
                        continue
                exit_index = j
                break
        else:
            held = last_index - entry_index

        gross = ((exit_price - entry) / entry if side == "LONG"
                 else (entry - exit_price) / entry)
        funding_cost = funding_bps_per_8h * (max(held, 1) * 5.0 / 480.0)
        total_cost = (2.0 * fee_bps + 2.0 * slippage_bps + funding_cost) / 10000.0
        net = gross - total_cost
        trades.append({
            "side": side, "signal_index": i, "entry_index": entry_index,
            "exit_index": exit_index, "entry": entry, "exit": exit_price,
            "exit_reason": exit_reason, "holding_bars": max(held, 1),
            "stop": stop, "target_2": target,
            "gross_return_pct": round(gross * 100.0, 5),
            "net_return_pct": round(net * 100.0, 5),
            "estimated_funding_bps": round(funding_cost, 4),
        })
        i = exit_index + 1

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
    avg_hold = sum(t["holding_bars"] for t in trades) / len(trades) if trades else 0.0
    total_funding = sum(t["estimated_funding_bps"] for t in trades)
    return {
        "trades": trades,
        "trade_count": len(trades),
        "win_rate_pct": round(100.0 * wins / len(trades), 2) if trades else 0.0,
        "net_return_pct": round((compounded - 1.0) * 100.0, 5),
        "profit_factor": round(gains / losses, 4) if losses else (None if gains == 0 else "infinite"),
        "max_drawdown_pct": round(max_drawdown * 100.0, 5),
        "average_trade_pct": round(sum(returns) / len(returns), 5) if returns else 0.0,
        "expectancy_pct": round(sum(returns) / len(returns), 5) if returns else 0.0,
        "average_holding_bars": round(avg_hold, 2),
        "holding_bars_cap": holding_bars,
        "round_trip_fee_bps": round(2.0 * fee_bps, 3),
        "round_trip_slippage_bps": round(2.0 * slippage_bps, 3),
        "estimated_total_funding_bps": round(total_funding, 4),
        "note": "Historical estimate only. Intrabar ambiguity resolves conservatively to stop first. Supply exchange-specific fees, slippage and funding; liquidation, partial fills and order-book queue position are not modeled.",
    }
