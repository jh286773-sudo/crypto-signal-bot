import sys

from toobit_api import ToobitAPI


def main():
    print("Testing Toobit public API connection...", flush=True)

    api = ToobitAPI()

    try:
        server = api.server_time()
        print("Server time: OK")
        print(server)

        price = api.get_price("BTCUSDT")
        if not price:
            raise RuntimeError("Price endpoint returned an empty response")

        print("\nBTC Price:")
        print(price)

        for timeframe in ["5m", "15m", "1h"]:
            candles = api.get_klines("BTCUSDT", timeframe, 100)

            print(f"\n{timeframe}: {len(candles)} candles received")
            if len(candles) < 60:
                raise RuntimeError(
                    f"Not enough candles for {timeframe}: {len(candles)}"
                )

            print("Latest candle:", candles[-1])

        print("\nTOOBIT CONNECTION TEST PASSED")

    except Exception as error:
        print("\nTOOBIT CONNECTION TEST FAILED:", error, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
