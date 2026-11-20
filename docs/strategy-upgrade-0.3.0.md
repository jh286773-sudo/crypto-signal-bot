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

## Official LuxAlgo SMC source — staged, not yet active in the app
- The upstream Pine Script source is stored at `third_party/luxalgo_smc/SmartMoneyConcepts_LuxAlgo.pine` with the original LuxAlgo copyright/license header intact.
- Source identity: TradingView's open-source listing, [Smart Money Concepts (SMC) [LuxAlgo]](https://www.tradingview.com/script/CnB3fSph-Smart-Money-Concepts-SMC-LuxAlgo/). The retrieved source copy was obtained from a public source mirror; it is not represented as a LuxAlgo-maintained GitHub repository.
- License text is included at `third_party/luxalgo_smc/LICENSE.txt` (CC BY-NC-SA 4.0). Any distributed adaptations must retain attribution, indicate modifications, remain non-commercial, and be shared under the same license.
- Important: preserving the official Pine source in the repository does not make Pine Script executable inside the current Python/Kotlin engines. The existing `_market_structure` / `structure()` implementation remains an independent approximation and is NOT the official LuxAlgo implementation.
- No official LuxAlgo-derived signal is enabled by this source-staging commit. The app integration still requires a verified Pine-compatible runtime or TradingView alert/webhook bridge, closed-candle/timeframe mapping, and parity tests before activation.
- TradingView account setup, chart attachment, alert creation, and a publicly reachable webhook endpoint require the user's own TradingView account and deployment environment; none is silently assumed or configured here.
