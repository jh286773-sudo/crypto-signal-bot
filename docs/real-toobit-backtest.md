# AURUM X — Real Toobit Walk-Forward Runner

## Run locally

Install requirements, then set the read-only Toobit API credentials in environment variables:

- TOOBIT_API_KEY
- TOOBIT_API_SECRET

Create the key with read permission only. The runner calls only the signed read-only futures commission endpoint; it does not submit, amend, or cancel orders.

Run:

    python run_toobit_backtest.py --symbol BTC-SWAP-USDT --days 30 --slippage-bps 2.0

The slippage argument must be a measured adverse per-fill estimate supplied by the operator. The runner downloads paginated historical 5m/15m/30m OHLCV, fetches historical funding settlement rates, retrieves the account-specific opening and closing taker fees, and writes a JSON report.

## Fee source

By default, actual account-specific rates are fetched from the signed Toobit USER_DATA endpoint. To run without API credentials, explicitly choose the currently published VIP-0 futures taker schedule:

    python run_toobit_backtest.py --symbol BTC-SWAP-USDT --days 30 --slippage-bps 2.0 --public-vip0-fees

VIP-0 is a published public schedule, not a claim about the user's personal fee tier. If the account has a different tier, use the authenticated account mode.

## GitHub Actions

Use Actions -> Toobit Real Historical Walk-Forward -> Run workflow. For account mode, configure repository Actions secrets named TOOBIT_API_KEY and TOOBIT_API_SECRET. Select the fee source, symbol, date window, and measured slippage. The result is uploaded as the artifact aurum-x-toobit-walk-forward-result.

## Model boundaries

- Kline pagination respects Toobit's 1000-bar page cap and verifies contiguous timestamps in the simulator.
- Funding is charged once per historical settlement event, not once per 5m bar. If the position exits before the settlement candle closes, that settlement is not charged.
- Open and close taker rates are distinct and come from the account endpoint.
- Position quantity is sized against the configured equity-risk budget after estimated round-trip opening/closing fees and adverse slippage at the modeled stop. Gaps and market impact can still exceed the estimate.
- Historical OHLCV still cannot reconstruct queue position, exact intrabar execution order, market impact, liquidation, or actual historical per-trade VIP fee changes. Current account rates are applied consistently across the selected historical period and this is stated in the output.
- Android monitors TP1/TP2/TP3 as staged exit reminders. The current app does not submit exchange orders or automatically execute partial closes.
- This is a backtest, not a profitability guarantee or live execution engine.


## Conservative same-bar exit handling

When TP1 is reached and the same OHLC candle also trades through the newly
raised breakeven stop, the simulator books TP1 and closes the remaining size at
the breakeven stop (with modeled adverse slippage). It does not assume that TP2
or TP3 filled first. This avoids optimistic sequencing that cannot be proven
from a single OHLC candle.

## Validation scope

The Python and Android strategy engines validate every OHLCV candle used by
recursive indicators. Non-finite values, negative volume, and impossible
high/low ranges invalidate the dataset and suppress a directional signal.

The walk-forward runner uses closed candles only and enters at the next 5m open.
This is not a full exchange matching-engine simulation: liquidation, queue
priority, market impact, partial fills, and exact intrabar event order are not
recoverable from OHLCV alone. A real performance report still requires a
measured adverse slippage input and either authenticated account commission
rates or the explicitly labeled public VIP-0 fee schedule.

Funding settlement timestamps are charged once. If a settlement and an intrabar
exit fall in the same 5m OHLC candle, exposure at that candle's open is used
because OHLC cannot establish the order of the settlement and exit; this avoids
optimistically omitting a potentially payable funding event.
