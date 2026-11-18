import os
import logging
import requests

from toobit_api import ToobitAPI
from signal_engine import analyze_market

SYMBOL = os.getenv("SYMBOL", "BTCUSDT")
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "").strip()
NTFY_SERVER = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip(" /")

logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(message)s")


def notify(title, message, priority="default"):
    if not NTFY_TOPIC:
        logging.warning("NTFY_TOPIC is not configured; phone notification skipped.")
        return
    if "/" in NTFY_TOPIC or not NTFY_TOPIC.replace("-", "").replace("_", "").isalnum():
        raise ValueError("NTFY_TOPIC must be a simple topic name.")
    response = requests.post(
        f"{NTFY_SERVER}/{NTFY_TOPIC}",
        data=message.encode("utf-8"),
        headers={"Title": title, "Priority": priority, "Tags": "chart_with_upwards_trend"},
        timeout=20,
    )
    response.raise_for_status()
    logging.info("Phone notification sent.")


def main():
    api = ToobitAPI()
    logging.info("Checking %s on Toobit public market data", SYMBOL)
    candles = api.get_multi_timeframe(SYMBOL)
    result = analyze_market(candles)

    signal = result.get("signal", "WAIT")
    price = result.get("price")
    logging.info("Price: %s | Signal: %s | Details: %s", price, signal, result)

    if signal not in ("LONG", "SHORT"):
        logging.info("No confirmed entry; no alert sent.")
        return

    atr = float(result.get("atr") or 0)
    if not price or atr <= 0:
        raise RuntimeError("Missing price or ATR; refusing to send an incomplete signal.")

    direction = 1 if signal == "LONG" else -1
    stop = price - direction * 1.5 * atr
    target = price + direction * 3.0 * atr
    message = (
        f"{SYMBOL} | {signal}\n"
        f"Entry reference: {price:.4f}\n"
        f"Stop-loss reference: {stop:.4f}\n"
        f"Take-profit reference: {target:.4f}\n"
        f"Score: {result.get('score', 'n/a')}\n"
        f"Reason: {'; '.join(result.get('reasons', []))}\n"
        "Signal only — no order was placed."
    )
    notify(f"{SYMBOL} {signal}", message, "high")


if __name__ == "__main__":
    main()
