# AURUM X 0.4.0 — causal walk-forward and execution model

## Timestamp contract
- All historical candle timestamps are Unix milliseconds and denote candle OPEN time.
- A candle is available at decision time only when open_time + interval_ms <= decision_time.
- The 5m decision candle is used only after its close. 15m/30m bars still forming at that instant are excluded.
- Timestamped 5m/15m/30m series must be strictly increasing and contiguous. Gaps are rejected rather than silently aligned by row index.

## Live signal feed
The Toobit Python gateway drops any candle whose close time is later than the request snapshot and then freezes 15m/30m histories to the latest closed 5m candle close. The Android gateway also filters forming candles; the Android strategy independently checks timestamps and trims higher timeframes as-of the 5m decision close.

## Walk-forward simulation
Use `ToobitAPI.get_historical_multi_timeframe()` to fetch longer real OHLCV windows, then call the public `backtest.run_backtest` API (or `walk_forward.run_walk_forward`). `fee_bps` and `slippage_bps` are mandatory, explicit per-side inputs; the simulator does not silently assume a fee tier. Fee and slippage assumptions are mandatory inputs; supply the actual account fee tier and measured slippage. The module never downloads, invents, or fills missing historical data.

Position sizing risks the configured equity fraction at the initial stop. The position is reduced at TP1/TP2/TP3 using a one-third default allocation; after TP1 the stop moves to entry. Stop is resolved before targets when both are touched in one OHLC bar. Fees and slippage are charged on entry and each exit fill. Funding is included only when historical per-candle `funding_rate` exists.

## Limits
This is a causal OHLCV walk-forward estimate, not a fill-accurate exchange simulator. It does not model liquidation, order-book queue priority, market impact, or partial fill uncertainty. A real profitability claim requires real historical data, documented fee tier, funding history, and out-of-sample walk-forward periods.

## Python/Android contract
Android market candles carry Unix-ms open times. The strategy must reject missing/non-monotonic timestamps and must trim 15m/30m histories to bars whose close time is no later than the latest closed 5m decision time. Shared golden test vectors and matching threshold/SMC assertions are used to detect drift; exact full-output parity is not claimed until all feature inputs and outputs are compared.
