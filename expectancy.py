"""Conservative, causal expectancy gate based only on previously closed net-R trades."""
from __future__ import annotations
from math import sqrt
from typing import Iterable

MIN_TRADES = 30
MIN_PROFIT_FACTOR = 1.10
ONE_SIDED_Z = 1.645


def expectancy_metrics(net_r: Iterable[float]) -> dict:
    values = [float(x) for x in net_r]
    if any(not __import__("math").isfinite(x) for x in values):
        raise ValueError("net-R history must contain only finite closed-trade results")
    n = len(values)
    if n < MIN_TRADES:
        return {"allowed": False, "count": n, "reason": "insufficient_closed_trades"}
    mean = sum(values) / n
    variance = sum((x - mean) ** 2 for x in values) / (n - 1)
    lower = mean - ONE_SIDED_Z * sqrt(variance / n)
    gross_profit = sum(x for x in values if x > 0)
    gross_loss = -sum(x for x in values if x < 0)
    pf = gross_profit / gross_loss if gross_loss > 0 else (float("inf") if gross_profit > 0 else 0.0)
    allowed = mean > 0 and lower > 0 and pf > MIN_PROFIT_FACTOR
    return {"allowed": allowed, "count": n, "mean_net_r": mean,
            "lower_95_net_r": lower, "profit_factor": pf,
            "reason": "positive_robust_expectancy" if allowed else "expectancy_not_proven"}


def expectancy_allows(stats: dict | None, side: str) -> tuple[bool, dict]:
    if side not in ("LONG", "SHORT"):
        return False, {"allowed": False, "reason": "invalid_side"}
    values = stats.get(side) if isinstance(stats, dict) else None
    if values is None:
        return False, {"allowed": False, "count": 0, "reason": "no_closed_trade_history"}
    result = expectancy_metrics(values)
    return bool(result["allowed"]), result
