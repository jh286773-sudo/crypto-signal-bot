"""Public backtest entry point; all runs use timestamp-safe walk-forward execution.

The old row-index-aligned simulator has been removed because equal row indices
across 5m/15m/30m do not represent the same instant and can leak future candles.
"""
from __future__ import annotations

from typing import Any, Dict, Tuple
from walk_forward import run_walk_forward


def run_backtest(timeframes: Dict[str, list], *, fee_bps: float = None,
                 fee_bps_open: float = None, fee_bps_close: float = None,
                 slippage_bps: float, holding_bars: int = 72,
                 funding_bps_per_8h: float = 0.0,
                 initial_equity: float = 10_000.0,
                 risk_fraction: float = 0.01,
                 allocation: Tuple[float, float, float] = (1/3, 1/3, 1/3)) -> Dict[str, Any]:
    """Run causal 5m/15m/30m walk-forward using explicit per-side costs.

    Funding fallback is intentionally disabled: callers must provide real
    historical funding settlement events to include funding costs.
    """
    if funding_bps_per_8h:
        raise ValueError("estimated funding fallback disabled; provide actual funding settlement events")
    if fee_bps is None and (fee_bps_open is None or fee_bps_close is None):
        raise ValueError("provide account-specific fee_bps_open and fee_bps_close")
    return run_walk_forward(
        timeframes,
        fee_bps_per_side=fee_bps,
        fee_bps_open=fee_bps_open,
        fee_bps_close=fee_bps_close,
        slippage_bps_per_side=slippage_bps,
        initial_equity=initial_equity,
        risk_fraction=risk_fraction,
        max_holding_bars=holding_bars,
        allocation=allocation,
    )
