# AURUM X 0.3.0 — direct indicator signal rules

## Timeframe selection
- The user may select any one or more supported timeframes: 1m, 3m, 5m, 15m, 30m, 1h, and 4h.
- With one selected timeframe, that timeframe is analyzed independently and may produce LONG or SHORT.
- With multiple selected timeframes, each is analyzed independently. A final LONG is emitted only when every selected timeframe is LONG; a final SHORT is emitted only when every selected timeframe is SHORT.
- Any disagreement, neutral analysis, missing/invalid candles, or unavailable common closed-candle boundary means NO SIGNAL.
- Unselected timeframes are never fetched as confirmation requirements and never gate a signal.

## Direct technical-indicator decision
- No indicator weights, timeframe weights, family weights, score totals, confidence scores, or score thresholds are used.
- Core directional conditions must all agree: EMA20/50/200 alignment, RSI directional range, MACD histogram direction, and DMI direction (DI+ versus DI-).
- Supporting conditions from VWAP, OBV trend, and the Bollinger middle band must not oppose the candidate direction.
- If core conditions do not align or a supporting indicator opposes them, the timeframe is neutral and emits no direction.
- SMC is an independent entry-confirmation filter, not a weighted indicator and not part of a score.
- SMC context includes confirmed BOS/CHoCH, bullish/bearish Order Blocks, liquidity sweeps, Equal Highs/Lows, directional Retest, and false-breakout detection.
- Each selected timeframe must independently confirm the same direction through a directional BOS/CHoCH, matching Retest, or opposing-side liquidity sweep. A false breakout or a clearly opposing active Order Block blocks the signal.
- Equal Highs/Lows and swing structure are exposed as context; they are not converted into points.
- Python and Android apply the same SMC confirmation rules.

## Runtime behavior
- Decisions use closed candles only. For multiple selected timeframes, data is aligned to a shared closed-candle decision boundary.
- Signals are read-only analysis; the application does not place exchange orders.
