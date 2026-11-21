import logging
import math
import os

from toobit_api import ToobitAPI
from signal_engine import analyze_market

MAJOR_BASE_ASSETS = ("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "LINK", "AVAX")
MAX_SYMBOLS = min(40, max(1, int(os.getenv("MAX_SYMBOLS", "10"))))
def main():
    api = ToobitAPI()
    selected_timeframes = api.configured_timeframes()
    symbols = api.get_futures_symbols(limit=MAX_SYMBOLS, preferred_bases=MAJOR_BASE_ASSETS)
    logging.info("Selected analysis timeframes: %s", ", ".join(selected_timeframes))
    logging.info("Scanning %d supported major-coin USDT perpetual contracts: %s",
                 len(symbols), ", ".join(symbols))

    checked = 0
    for symbol in symbols:
        try:
            # Only configured intervals are fetched and passed to the strategy.
            candles = api.get_multi_timeframe(symbol, selected_timeframes=selected_timeframes)
            result = analyze_market(candles, selected_timeframes=selected_timeframes)
            signal = result.get("signal", "NO_SIGNAL")
            reports = result.get("timeframes", {})
            entry_tf = result.get("entry_timeframe") or next(iter(reports), None)
            entry_report = reports.get(entry_tf, {}) if entry_tf else {}
            price = result.get("entry") or entry_report.get("close")
            checked += 1

            logging.info("%s | price=%s | signal=%s | reason=%s",
                         symbol, price, signal, result.get("reason", ""))
            if signal in ("LONG", "SHORT") and price is not None:
                logging.info("%s directional signal detected: %s at %s (no push notification configured)",
                             symbol, signal, price)
        except Exception:
            logging.exception("Analysis/notification failed for %s; continuing with next contract", symbol)

    logging.info("Scan complete: %d contracts checked; push notifications are disabled", checked)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(message)s")
    main()
