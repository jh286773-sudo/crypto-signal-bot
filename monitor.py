import logging
import math
import os
import requests

from toobit_api import ToobitAPI
from signal_engine import analyze_market

MAJOR_BASE_ASSETS = ("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "LINK", "AVAX")
MAX_SYMBOLS = min(10, max(1, int(os.getenv("MAX_SYMBOLS", "10"))))
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "").strip()
NTFY_SERVER = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip(" /")


def notify(title, message, priority="default"):
    if not NTFY_TOPIC:
        logging.warning("NTFY_TOPIC is not configured; phone notification skipped.")
        return False
    if "/" in NTFY_TOPIC or not NTFY_TOPIC.replace("-", "").replace("_", "").isalnum():
        raise ValueError("NTFY_TOPIC must be a simple topic name.")
    response = requests.post(
        f"{NTFY_SERVER}/{NTFY_TOPIC}",
        data=message.encode("utf-8"),
        headers={"Title": title, "Priority": priority, "Tags": "chart_with_upwards_trend"},
        timeout=20,
    )
    response.raise_for_status()
    logging.info("Phone notification sent: %s", title)
    return True


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
    symbols = api.get_futures_symbols(limit=MAX_SYMBOLS, preferred_bases=MAJOR_BASE_ASSETS)
    logging.info("Scanning %d supported major-coin USDT perpetual contracts: %s",
                 len(symbols), ", ".join(symbols))

    checked = 0
    alerts = 0
    for symbol in symbols:
        try:
            candles = api.get_multi_timeframe(symbol)
            # The same no-score, selected-timeframe unanimous engine is used for
            # every Python monitor cycle. No expectancy/score gate is applied.
            result = analyze_market(candles)
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

            message = format_signal_alert(price, signal)
            if notify(symbol, message, "high"):
                alerts += 1
        except Exception:
            logging.exception("Analysis failed for %s; continuing with next contract", symbol)

    logging.info("Scan complete: %d contracts checked, %d alerts sent", checked, alerts)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(message)s")
    main()
