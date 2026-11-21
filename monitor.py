import logging
import math
import os

from toobit_api import ToobitAPI
from signal_engine import analyze_market

MAJOR_BASE_ASSETS = ("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "LINK", "AVAX")
MAX_SYMBOLS = min(10, max(1, int(os.getenv("MAX_SYMBOLS", "10"))))
def format_signal_alert(price, signal):
    if signal not in ("LONG", "SHORT"):
        raise ValueError("Only LONG and SHORT can be sent as entry alerts.")
    value = float(price)
    if not math.isfinite(value) or value <= 0:
        raise ValueError("Entry price must be finite and positive.")
    if value >= 100:
        formatted = f"{value:.2f}"
    elif value >= 1:
        formatted = f"{value:.4f}"
    else:
        formatted = f"{value:.7f}"
    return f"{formatted} ورود {signal}"



def main():
    api = ToobitAPI()
    selected_timeframes = api.configured_timeframes()
    symbols = api.get_futures_symbols(limit=MAX_SYMBOLS, preferred_bases=MAJOR_BASE_ASSETS)
    logging.info("Selected analysis timeframes: %s", ", ".join(selected_timeframes))
    logging.info("Scanning %d supported major-coin USDT perpetual contracts: %s",
                 len(symbols), ", ".join(symbols))

    checked = 0
    signals_detected = 0
    for symbol in symbols:
        try:
            # Only configured/selected intervals are fetched; unselected intervals
            # are not downloaded, analyzed, or allowed to affect the signal.
            candles = api.get_multi_timeframe(symbol, selected_timeframes=selected_timeframes)
            # The same no-score, selected-timeframe unanimous engine is used for
            # every Python monitor cycle. No expectancy/score gate is applied.
            result = analyze_market(candles, selected_timeframes=selected_timeframes)
            signal = result.get("signal", "NO_SIGNAL")
            reports = result.get("timeframes", {})
            entry_tf = result.get("entry_timeframe") or next(iter(reports), None)
            entry_report = reports.get(entry_tf, {}) if entry_tf else {}
            price = result.get("entry") or entry_report.get("close")
            checked += 1

            logging.info("%s | price=%s | signal=%s | reason=%s",
                         symbol, price, signal, result.get("reason", ""))
            if signal not in ("LONG", "SHORT"):
                continue
            if price is None or float(price) <= 0:
                logging.warning("%s: missing valid entry price; alert skipped", symbol)
                continue

            format_signal_alert(price, signal)  # Validate the same compact alert format used by the Android app.
            signals_detected += 1
            logging.info("%s | directional signal detected; delivery is handled only by the Android app", symbol)
        except Exception:
            logging.exception("Analysis failed for %s; continuing with next contract", symbol)

    logging.info("Scan complete: %d contracts checked, %d directional signals detected; no push notifications sent", checked, signals_detected)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(message)s")
    main()
