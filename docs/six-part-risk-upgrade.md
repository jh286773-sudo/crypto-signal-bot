# AURUM X — six-part risk and signal quality upgrade

This change strengthens the existing read-only signal workflow; it does not submit exchange orders.

## 1. Entry quality
The engine requires closed 5m/15m/30m candles, full directional timeframe alignment, indicator-family consensus, confirmed SMC break/sweep and retest, and rejects false breaks and entries more than 0.80 ATR from retest. Insufficient conditions remain NO_SIGNAL/WAIT.

## 2. Smart entry
The Android monitor evaluates only when a new closed 5m candle arrives. Walk-forward enters at the next 5m open, avoiding same-candle look-ahead.

## 3. Adaptive stop and targets
ATR and confirmed swing structure determine bounded structural risk with 1.5R/2.5R/3.5R targets. Position sizing includes modeled fees and adverse slippage.

## 4. Profit protection
After TP1, remaining simulated size is protected at entry. After TP2, remaining size is protected at TP1. Same-bar target/stop ambiguity is resolved conservatively. Android mirrors TP2-to-TP1 as local alert state only; it does not submit exchange stop or partial-close orders.

## 5. Market condition filters
Existing filters block abnormal ATR expansion and require volume/order-flow/structure and timeframe agreement. Bid/ask spread and order-book depth are not supplied by the current adapter, so no fabricated spread/liquidity check is claimed.

## 6. Validation and diagnostics
Walk-forward output now includes LONG/SHORT trade counts, win rates and net PnL, profit factor, and maximum consecutive losing trades.

## Limitations
No strategy guarantees loss-free trades. This is a signal/alert system, not an order-execution system. OHLCV backtests cannot model liquidation, queue priority, market impact, or exact intrabar event ordering.

## Additional loss-reduction controls

- Default walk-forward risk per signal is reduced from 1.0% to 0.5% of current equity. This changes simulated position sizing; it does not guarantee positive expectancy.
- The walk-forward engine pauses new entries for 288 five-minute bars (24 hours) after two consecutive full stop-loss exits, then resets the full-stop streak counter. The report includes cooldown count and skipped bars.
- The Android monitor applies per-symbol protection: after two full stop-loss exits within a rolling 24-hour window, new alerts for that symbol are paused for 24 hours. Stops reached after TP1 are not counted as full-stop losses; a completed TP3 resets the streak.
- The Toobit range loader retries missing candle timestamps using the exchange endpoint only. It never fabricates candles; unrecoverable gaps still fail the backtest.
- The live Android app remains a signal/alert monitor. The cooldown can suppress alerts, but it cannot close exchange positions or guarantee a stop order was filled.
