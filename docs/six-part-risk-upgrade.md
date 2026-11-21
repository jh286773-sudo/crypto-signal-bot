# AURUM X — Strategy and risk-policy record

This document is aligned with the active `main` branch. Older revisions of
this note described fixed 5m/15m/30m consensus, staged TP exits, stop-loss
streak pauses, and indicator-family scoring. Those are not the active policy.

## 1. Entry direction
The active engine uses direct indicator conditions without weighted scores,
points, confidence scores, or timeframe weights. EMA trend and DMI pressure
must align, with RSI or MACD providing momentum confirmation. VWAP, OBV, and
Bollinger are supporting diagnostics, not score contributions.

## 2. User-selected timeframes
The user may select any non-empty combination of 1m, 3m, 5m, 15m, 30m, 1h,
and 4h. A single selected interval is independently sufficient for a signal.
When multiple intervals are selected, every selected interval must agree.
Unselected intervals are not fetched or analyzed. Each selected interval uses
its own latest closed candle; no shared close boundary is required.

## 3. Smart Money Concepts
SMC is a separate entry confirmation required on every selected interval.
False breakouts, opposing order-block context, or missing structural
confirmation reject the entry. SMC is not converted into a score or weight.

## 4. Exceptional volatility and data integrity
Exceptional ATR expansion blocks new entries. Missing, stale, malformed, or
non-contiguous candle data produces WAIT / NO_SIGNAL. Real exchange timestamps
and OHLCV are required; synthetic candles are never created.

## 5. Position policy
LONG is the only position-opening direction. SHORT closes an existing LONG and
never opens a SHORT. WAIT holds an existing LONG. The active hold-until-SHORT
model has no active stop-loss or take-profit exits and no automatic pause after
two losses. ATR may be used as a sizing reference in historical simulation,
but it is not an active stop.

## 6. Alerts and validation
Android signal alerts use exactly `[price] ورود LONG` or
`[price] ورود SHORT`, with no indicator or symbol text added to the alert
content. SHORT is delivered as an exit-only alert even if no local LONG
position is tracked. Unit tests cover formatting, deduplication, timeframe
selection, indicator direction, SMC, volatility, and candle freshness.

## Backtest scope
The walk-forward model uses real Toobit historical candles and funding events
when available, explicit fee/slippage assumptions, and records actual data
start times and incomplete symbols. The fastest selected interval is used
only as the executable simulation clock; each other selected interval
contributes its own latest candle already closed at each decision time.
Historical OHLCV cannot prove queue position, market impact, liquidation,
partial fills, or exact intrabar ordering. Backtest output is not a guarantee
of future profitability.
