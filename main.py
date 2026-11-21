

import time
import logging

from toobit_api import ToobitAPI
from signal_engine import analyze_market

SYMBOL = "BTC-SWAP-USDT"
CHECK_INTERVAL = 30

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s"
)

def main():
    api = ToobitAPI()
    selected_timeframes = api.configured_timeframes()

    logging.info("Starting Crypto Signal Bot")
    logging.info("Selected analysis timeframes: %s", ", ".join(selected_timeframes))
    logging.info("Symbol: %s", SYMBOL)

    while True:
        try:
            candles = api.get_multi_timeframe(SYMBOL, selected_timeframes=selected_timeframes)

            result = analyze_market(candles, selected_timeframes=selected_timeframes)

            entry_tf = result.get("entry_timeframe")
            entry_price = result.get("entry")
            if entry_price is None and entry_tf:
                entry_price = result.get("timeframes", {}).get(entry_tf, {}).get("close")
            logging.info("Price (%s): %s", entry_tf or "selected timeframes", entry_price)
            logging.info("Signal: %s", result.get("signal"))
            logging.info("Reason: %s", result.get("reason", ""))
            logging.info("Details: %s", result)

        except Exception:
            logging.exception("Analysis error")

        time.sleep(CHECK_INTERVAL)


if __name__ == "__main__":
    main()
