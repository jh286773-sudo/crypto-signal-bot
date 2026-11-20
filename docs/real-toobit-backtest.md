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
- Historical OHLCV still cannot reconstruct queue position, exact intrabar execution order, market impact, liquidation, or actual historical per-trade VIP fee changes. Current account rates are applied consistently across the selected historical period and this is stated in the output.
- This is a backtest, not a profitability guarantee or live execution engine.
