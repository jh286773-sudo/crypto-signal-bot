# AURUM X

Read-only Toobit USDT perpetual signal monitor and historical walk-forward research tools.

## ntfy phone alerts

The scheduled GitHub Actions workflow `.github/workflows/signal-monitor.yml` runs the Python monitor every five minutes. Directional signals are published to the ntfy app using the exact message body:

- `[price] ورود LONG`
- `[price] ورود SHORT`

Configure the repository Actions secret `NTFY_TOPIC` with the topic name subscribed to in the ntfy phone app. For compatibility, a repository Actions variable with the same name is also accepted. If the topic is private, configure `NTFY_TOKEN` as an Actions secret. The default server is `https://ntfy.sh`; an alternate HTTPS server can be supplied with `NTFY_SERVER`.

The monitor persists per-symbol signal state in `aurum_ntfy_state.json` through the Actions cache. Repeated same-direction alerts are suppressed until the symbol returns to WAIT/NO_SIGNAL or changes direction. Failed ntfy delivery is not marked as sent, so a later scan can retry.

Set the repository Actions variable `SELECTED_TIMEFRAMES` to a comma-separated selection from `1m,3m,5m,15m,30m,1h,4h`. A single interval is valid. Only selected intervals are downloaded and analyzed. Android app selections are stored locally on the phone and do not automatically synchronize to GitHub Actions.

Android app alerts use the device's local notification service. They are a separate delivery path from Python/GitHub Actions to ntfy.

## Strategy contract

- Every selected timeframe is analyzed independently using its own latest closed candle.
- Multiple selected timeframes must all agree; one selected timeframe can decide independently.
- No weighted scores, weighted votes, or timeframe weighting are used.
- SMC confirmation and the exceptional-volatility gate remain separate entry filters.
- LONG is the only position-opening direction. SHORT is exit-only and never opens a short position.
- WAIT/NO_SIGNAL creates no directional alert.
- The Python monitor is read-only and does not submit exchange orders.

## Validation

Python strategy and monitor tests run in GitHub Actions. The Android workflow builds a debug APK, runs JVM parity tests, and uploads the APK artifact. Real Toobit backtests require available historical candles for every selected symbol/timeframe; incomplete data is reported as a failed/incomplete run rather than being silently synthesized.
