# AURUM X

Read-only Toobit USDT perpetual signal monitor and historical walk-forward research tools.

## Signal monitoring

The scheduled GitHub Actions workflow `.github/workflows/signal-monitor.yml` runs the Python monitor every five minutes. It scans selected markets and records detected signals in workflow logs only. Push notifications from ntfy and GitHub Actions are disabled. Signal notifications are delivered only by the AURUM X Android application through the phone's local notification service.

Set the repository Actions variable `SELECTED_TIMEFRAMES` to a comma-separated selection from `1m,3m,5m,15m,30m,1h,4h`. A single interval is valid. Only selected intervals are downloaded and analyzed. Android app selections are stored locally on the phone and do not automatically synchronize to GitHub Actions.

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
