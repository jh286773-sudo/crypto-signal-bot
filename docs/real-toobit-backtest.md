# AURUM X — Real Toobit Walk-Forward Runner

## Run locally

Install the project requirements, then run a selected-symbol historical test:

```bash
python run_toobit_multi_backtest.py \
  --symbols BTC-SWAP-USDT,ETH-SWAP-USDT \
  --timeframes 3m,15m \
  --days 30 \
  --slippage-bps 1.0
```

The runner uses Toobit's public historical OHLCV and funding-settlement data.
The multi-symbol runner currently labels its commission assumption as the
published VIP-0 taker rate (6 bps per side); it does not claim that this is
the user's authenticated account-specific fee tier. Slippage is an explicit
adverse per-fill assumption supplied with `--slippage-bps`, not a measured
historical fill statistic unless the operator has independently measured it.

## Selecting timeframes

Supported intervals are `1m`, `3m`, `5m`, `15m`, `30m`, `1h`, and
`4h`. Pass any non-empty combination with `--timeframes`, or configure
`SELECTED_TIMEFRAMES` for Python monitor/backtest processes.

- A single selected interval is valid and is analyzed independently.
- With multiple selected intervals, all selected directions must agree.
- Unselected intervals are neither downloaded nor used in the signal decision.
- Each interval uses its own latest fully closed candle. No common candle
  boundary or row-index alignment is required.
- The fastest selected interval is used only as the walk-forward execution
  clock to model the next executable entry/exit open. At each decision time,
  slower intervals contribute their own latest candle already closed by then.

Examples:

```bash
# Independent 30-minute strategy
python run_toobit_multi_backtest.py --symbols BTC-SWAP-USDT --timeframes 30m --days 30 --slippage-bps 1.0

# Require agreement among 3m, 5m and 15m
python run_toobit_multi_backtest.py --symbols BTC-SWAP-USDT --timeframes 3m,5m,15m --days 30 --slippage-bps 1.0
```

## Active position model

- LONG opens the only supported position type.
- SHORT closes an existing LONG and never opens a SHORT position.
- WAIT keeps an existing LONG open.
- No active stop-loss or take-profit exit is used in this signal-reversal
  model; a LONG is held until a confirmed SHORT.
- The Python and Android engines use the same direct indicator-direction
  conditions, with no score, points, or indicator/timeframe weighting.
- SMC must independently confirm the direction on every selected interval.
  Exceptional volatility blocks new entries.

## Data integrity and report

The runner validates timestamp alignment, candle ordering, OHLCV values,
contiguous intervals, and historical pagination. It does not synthesize or
silently fill missing candles. The report includes:

- Requested UTC start and actual first retrieved candle time for every
  symbol/timeframe.
- Selected intervals and the fastest execution interval.
- Candle counts, fee and slippage assumptions, and historical funding events.
- Per-symbol results, failed symbols, and explicit data-completeness flags.
- A failure exit status when the selected-symbol backtest is incomplete.

The report is a historical simulation, not a live execution result or a
profitability guarantee. OHLCV data cannot reconstruct queue priority,
market impact, liquidation, partial fills, or exact intrabar event ordering.
