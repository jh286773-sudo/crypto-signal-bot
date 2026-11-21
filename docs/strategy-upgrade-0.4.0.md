# AURUM X — Current strategy contract (supersedes the earlier 0.4.0 draft)

This document records the active strategy behavior in the current `main` branch.
The earlier 0.4.0 draft described a fixed 5m/15m/30m decision clock and staged
stop/target exits; those rules are not the active AURUM X policy.

## Timeframe selection
- Supported intervals: `1m`, `3m`, `5m`, `15m`, `30m`, `1h`, `4h`.
- Any non-empty combination is valid, including a single interval.
- Only selected intervals are fetched and analyzed. Unselected intervals do
  not participate in signal direction, freshness, or validation.
- Each selected interval is checked using its own latest fully closed candle.
  There is no shared candle-close boundary or row-index alignment requirement.
- One selected interval can independently produce LONG or SHORT. With multiple
  selected intervals, every selected interval must agree; disagreement or a
  neutral result means no signal.

## Direction and entry filters
- Indicator direction uses direct logical conditions, not weights, points,
  weighted sums, confidence scores, or score thresholds.
- EMA trend and DMI directional pressure must agree. RSI midpoint momentum or
  MACD histogram momentum must additionally confirm that direction.
- VWAP, OBV, and Bollinger direction are reported as supporting diagnostics;
  they are not added to a score and do not independently create a signal.
- SMC is a separate structural confirmation required on every selected
  interval. A false breakout or missing directional SMC confirmation blocks
  the entry.
- Exceptional volatility blocks new entries.
- Insufficient, stale, malformed, or non-contiguous candle data produces
  WAIT / NO_SIGNAL, never a forced LONG or SHORT.

## Position and alert policy
- The strategy is read-only; it does not place exchange orders.
- LONG is the only position-opening direction.
- SHORT is an exit-only signal. It closes an existing LONG and never opens a
  SHORT position. A confirmed SHORT can still be delivered as an alert when
  no locally tracked LONG exists.
- WAIT does not open or close a position.
- No active stop-loss or take-profit exit is used in the current
  signal-reversal hold policy. A LONG remains open until a confirmed SHORT.
- The Android signal alert text is exactly `[price] ورود LONG` or
  `[price] ورود SHORT`, with price first and no indicator diagnostics.

## Candle and historical-data contract
- Exchange candle timestamps are Unix milliseconds and identify candle open
  time. Only candles whose close boundary has passed are eligible.
- Every selected interval is validated independently for sufficient history,
  valid OHLCV values, aligned timestamps, and contiguous candle spacing.
- The Toobit historical runner fetches only selected intervals and records the
  actual first retrieved candle time for each symbol and interval.
- No synthetic candles are generated and missing candles are not silently
  filled. An incomplete symbol/data set must be reported as incomplete.
- Backtest reports disclose the requested and actual data start, selected
  intervals, fees, slippage assumptions, funding data, failed symbols, and
  completeness status.

## Walk-forward execution assumptions
The simulator uses the fastest selected interval as its execution clock so
entries and exits can be modeled at the next available execution candle open.
At each decision point, every selected interval contributes only its own
latest candle that had fully closed by that decision time. This is a causal
execution constraint, not a requirement that the selected intervals share
candle boundaries.

Fees and adverse slippage are modeled explicitly. Funding is included only
when real historical settlement events are supplied. OHLCV cannot reconstruct
queue position, market impact, liquidation, partial fills, or exact intrabar
event ordering; results are estimates, not a profitability guarantee.
