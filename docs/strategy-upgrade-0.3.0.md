# AURUM X 0.3.0 strategy upgrade

## Entry workflow
- Preparation: 15m and 30m directional context must align.
- Confirmation: closed-candle BOS/CHoCH displacement or a liquidity sweep.
- Entry: the 5m candle must revisit the broken swing level and close back in the intended direction.
- A wick-only sweep / failed breakout does not produce an entry by itself.

## Risk levels
- Stop placement uses the confirmed swing invalidation level with an ATR buffer.
- Structural risk is bounded to 0.25–3.0 ATR; otherwise the engine falls back to 1.5 ATR.
- Targets are 1.5R, 2.5R and 3.5R.

## Backtest assumptions
- Signal on a closed candle, entry at the next 5m open.
- OHLC bars are checked for stop/target exits.
- If stop and target are both touched in one bar, stop is assumed first.
- Fees and slippage are charged round-trip; funding is scaled by actual holding time.
- This remains a historical estimate: liquidation, partial fills, order-book queue position and exchange-specific intrabar execution are not modeled.

## Engine parity
Python and Android use the same 5m trigger threshold, retest rules, structural stop bounds and reward/risk multiples. The engines remain read-only and do not place exchange orders.
