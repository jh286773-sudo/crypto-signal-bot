

from toobit_api import ToobitAPI


def main():
    print("Testing Toobit connection...")

    api = ToobitAPI()

    try:
        server = api.server_time()
        print("Server time: OK")
        print(server)

        price = api.get_price("BTCUSDT")
        print("\nBTC Price:")
        print(price)

        for timeframe in ["5m", "15m", "1h"]:
            candles = api.get_klines(
                "BTCUSDT",
                timeframe,
                100
            )

            print(
                f"\n{timeframe}: "
                f"{len(candles)} candles received"
            )

            if candles:
                print("Latest candle:", candles[-1])

        print("\nCONNECTION TEST COMPLETED")

    except Exception as error:
        print("\nCONNECTION FAILED:")
        print(error)


if __name__ == "__main__":
    main()
