

"""Smart Money Concepts analysis for OHLCV candles.

Each candle must contain open, high, low, close.
This module analyzes market data and never places trades.
"""
from typing import Any, Dict, List, Optional, Tuple


def analyze_smc(candles: List[Dict[str, Any]], swing: int = 3) -> Dict[str, Any]:
    """Detect market structure, liquidity sweeps, FVGs, order blocks and PD zones."""
    if swing < 1 or len(candles) < 2 * swing + 3:
        return {"valid": False, "reason": "Not enough candles"}

    op = [float(c["open"]) for c in candles]
    hi = [float(c["high"]) for c in candles]
    lo = [float(c["low"]) for c in candles]
    cl = [float(c["close"]) for c in candles]

    piv_hi: List[Tuple[int, float]] = []
    piv_lo: List[Tuple[int, float]] = []

    for i in range(swing, len(candles) - swing):
        if hi[i] == max(hi[i-swing:i+swing+1]):
            piv_hi.append((i, hi[i]))
        if lo[i] == min(lo[i-swing:i+swing+1]):
            piv_lo.append((i, lo[i]))

    last_hi = piv_hi[-1] if piv_hi else None
    last_lo = piv_lo[-1] if piv_lo else None
    prev_hi = piv_hi[-2] if len(piv_hi) > 1 else None
    prev_lo = piv_lo[-2] if len(piv_lo) > 1 else None

    trend = "range"
    if last_hi and last_lo and prev_hi and prev_lo:
        if last_hi[1] > prev_hi[1] and last_lo[1] > prev_lo[1]:
            trend = "bullish"
        elif last_hi[1] < prev_hi[1] and last_lo[1] < prev_lo[1]:
            trend = "bearish"

    event = None
    if last_hi and cl[-1] > last_hi[1]:
        event = "BOS_up" if trend != "bearish" else "CHoCH_up"
    elif last_lo and cl[-1] < last_lo[1]:
        event = "BOS_down" if trend != "bullish" else "CHoCH_down"

    # Fair Value Gap
    fvg: Optional[Dict[str, Any]] = None
    if lo[-1] > hi[-3]:
        fvg = {"low": hi[-3], "high": lo[-1], "type": "bullish"}
    elif hi[-1] < lo[-3]:
        fvg = {"low": hi[-1], "high": lo[-3], "type": "bearish"}

    # Liquidity sweep
    sweep = None
    if last_hi and hi[-1] > last_hi[1] and cl[-1] < last_hi[1]:
        sweep = "buy_side"
    elif last_lo and lo[-1] < last_lo[1] and cl[-1] > last_lo[1]:
        sweep = "sell_side"

    # Approximate Order Block
    order_block = None
    if event in ("BOS_up", "CHoCH_up"):
        for j in range(len(candles)-2, max(-1, len(candles)-13), -1):
            if cl[j] < op[j]:
                order_block = {
                    "low": lo[j], "high": hi[j], "type": "bullish"
                }
                break
    elif event in ("BOS_down", "CHoCH_down"):
        for j in range(len(candles)-2, max(-1, len(candles)-13), -1):
            if cl[j] > op[j]:
                order_block = {
                    "low": lo[j], "high": hi[j], "type": "bearish"
                }
                break

    recent_hi = max(hi[-50:])
    recent_lo = min(lo[-50:])
    midpoint = (recent_hi + recent_lo) / 2

    pd_zone = (
        "discount" if cl[-1] < midpoint
        else "premium" if cl[-1] > midpoint
        else "equilibrium"
    )

    return {
        "valid": True,
        "market_structure": trend,
        "structure_event": event,
        "swing_high": last_hi[1] if last_hi else None,
        "swing_low": last_lo[1] if last_lo else None,
        "liquidity_sweep": sweep,
        "order_block": order_block,
        "fair_value_gap": fvg,
        "dealing_range": {
            "high": recent_hi,
            "low": recent_lo,
            "midpoint": midpoint,
        },
        "premium_discount": pd_zone,
    }
