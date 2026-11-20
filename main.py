

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

    logging.info("Starting Crypto Signal Bot")
    logging.info("Symbol: %s", SYMBOL)

    while True:
        try:
            candles = api.get_multi_timeframe(SYMBOL)

            result = analyze_market(candles)

            logging.info("Price: %s", result.get("timeframes", {}).get("5m", {}).get("close"))
            logging.info("Signal: %s", result.get("signal"))
            logging.info("Reason: %s", result.get("reason", ""))
            logging.info("Details: %s", result)

        except Exception:
            logging.exception("Analysis error")

        time.sleep(CHECK_INTERVAL)


if __name__ == "__main__":
    main()
