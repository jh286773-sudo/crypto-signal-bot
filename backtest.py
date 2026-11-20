"""Public backtest entry point; all runs use timestamp-safe walk-forward execution.

The old row-index-aligned simulator has been removed because equal row indices
across 5m/15m/30m do not represent the same instant and can leak future candles.
"""
from __future__ import annotations

from typing import Any, Dict, Tuple
from walk_forward import run_walk_forward


def run_backtest(timeframes: Dict[str, list], *, fee_bps: float,
                 slippage_bps: float, holding_bars: int = 72,
                 funding_bps_per_8h: float = 0.0,
                 initial_equity: float = 10_000.0,
                 risk_fraction: float = 0.01,
                 allocation: Tuple[float, float, float] = (1/3, 1/3, 1/3)) -> Dict[str, Any]:
    """Run causal 5m/15m/30m walk-forward using explicit per-side costs.

    Funding fallback is intentionally disabled: callers must provide real
    historical per-candle funding_rate values to include funding costs.
    """
    if funding_bps_per_8h:
        raise ValueError("estimated funding fallback disabled; provide historical funding_rate on 5m candles")
    return run_walk_forward(
        timeframes,
        fee_bps_per_side=fee_bps,
        slippage_bps_per_side=slippage_bps,
        initial_equity=initial_equity,
        risk_fraction=risk_fraction,
        max_holding_bars=holding_bars,
        allocation=allocation,
    )
