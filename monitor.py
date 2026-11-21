import json
import logging
import math
import os
import re
from pathlib import Path

import requests

from toobit_api import ToobitAPI
from signal_engine import analyze_market

MAJOR_BASE_ASSETS = ("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "LINK", "AVAX")
MAX_SYMBOLS = min(40, max(1, int(os.getenv("MAX_SYMBOLS", "10"))))
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "").strip()
NTFY_SERVER = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip(" /")
NTFY_TOKEN = os.getenv("NTFY_TOKEN", "").strip()
NTFY_STATE_PATH = Path(os.getenv("AURUM_NTFY_STATE", "aurum_ntfy_state.json"))


def notify(message, priority="high"):
    """Publish the exact approved signal line to the user's ntfy topic."""
    if not NTFY_TOPIC:
        logging.error("NTFY_TOPIC is not configured; ntfy delivery is unavailable.")
        return False
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", NTFY_TOPIC):
        raise ValueError("NTFY_TOPIC must be a simple topic name.")
    if not NTFY_SERVER.startswith("https://"):
        raise ValueError("NTFY_SERVER must use HTTPS.")
    headers = {"Priority": priority}
    if NTFY_TOKEN:
        headers["Authorization"] = f"Bearer {NTFY_TOKEN}"
    response = requests.post(
        f"{NTFY_SERVER}/{NTFY_TOPIC}",
        data=message.encode("utf-8"),
        headers=headers,
        timeout=20,
    )
    response.raise_for_status()
    logging.info("ntfy phone notification delivered.")
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


def _load_signal_state():
    try:
        data = json.loads(NTFY_STATE_PATH.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, ValueError, TypeError):
        logging.exception("Could not read persisted ntfy deduplication state; starting empty.")
        return {}


def _save_signal_state(state):
    temporary = NTFY_STATE_PATH.with_suffix(NTFY_STATE_PATH.suffix + ".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    os.replace(temporary, NTFY_STATE_PATH)


def main():
    api = ToobitAPI()
    selected_timeframes = api.configured_timeframes()
    symbols = api.get_futures_symbols(limit=MAX_SYMBOLS, preferred_bases=MAJOR_BASE_ASSETS)
    state = _load_signal_state()
    logging.info("Selected analysis timeframes: %s", ", ".join(selected_timeframes))
    logging.info("Scanning %d supported major-coin USDT perpetual contracts: %s",
                 len(symbols), ", ".join(symbols))

    checked = 0
    alerts_sent = 0
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
            if signal not in ("LONG", "SHORT"):
                # A neutral interval between directional alerts permits a later
                # fresh alert, matching Android's last-signal deduplication.
                state[symbol] = "WAIT"
                _save_signal_state(state)
                continue
            if price is None or float(price) <= 0:
                logging.warning("%s: missing valid entry price; alert skipped", symbol)
                continue

            if state.get(symbol) == signal:
                continue
            message = format_signal_alert(price, signal)
            if notify(message):
                state[symbol] = signal
                _save_signal_state(state)
                alerts_sent += 1
        except Exception:
            logging.exception("Analysis/notification failed for %s; continuing with next contract", symbol)

    logging.info("Scan complete: %d contracts checked, %d ntfy alerts sent", checked, alerts_sent)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(message)s")
    main()
