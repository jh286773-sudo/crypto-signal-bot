import logging
import os
import requests

from toobit_api import ToobitAPI
from signal_engine import analyze_market

MAX_SYMBOLS = int(os.getenv("MAX_SYMBOLS", "40"))
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "").strip()
NTFY_SERVER = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip(" /")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s",
)


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
    logging.info("Phone notification sent: %s", title)


def main():
    api = ToobitAPI()
    symbols = api.get_futures_symbols(limit=MAX_SYMBOLS)
    logging.info("Scanning %d most liquid active USDT perpetual contracts", len(symbols))

    checked = 0
    alerts = 0
    for symbol in symbols:
        try:
            candles = api.get_multi_timeframe(symbol)
            result = analyze_market(candles)
            signal = result.get("signal", "WAIT")
            price = result.get("price")
            checked += 1

            logging.info(
                "%s | price=%s | signal=%s | score=%s | reason=%s",
                symbol, price, signal, result.get("score"),
                result.get("reason", "; ".join(result.get("reasons", []))),
            )

            if signal not in ("LONG", "SHORT"):
                continue

            atr = float(result.get("atr") or 0)
            if not price or atr <= 0:
                logging.warning("%s: missing price or ATR; alert skipped", symbol)
                continue

            direction = 1 if signal == "LONG" else -1
            stop = price - direction * 1.5 * atr
            target = price + direction * 3.0 * atr
            message = (
                f"{symbol} | {signal}\n"
                f"Entry reference: {price:.8g}\n"
                f"Stop-loss reference: {stop:.8g}\n"
                f"Take-profit reference: {target:.8g}\n"
                f"Score: {result.get('score', 'n/a')}\n"
                f"Reason: {'; '.join(result.get('reasons', []))}\n"
                "Signal only — no order was placed."
            )
            notify(f"{symbol} {signal}", message, "high")
            alerts += 1

        except Exception:
            logging.exception("Analysis failed for %s; continuing with next contract", symbol)

    logging.info("Scan complete: %d contracts checked, %d alerts sent", checked, alerts)


if __name__ == "__main__":
    main()
