
import time
import requests


class ToobitAPI:
    BASE_URL = "https://api.toobit.com"

    INTERVALS = {
        "5m": 5 * 60 * 1000,
        "15m": 15 * 60 * 1000,
        "1h": 60 * 60 * 1000
    }

    def __init__(self):
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": "CryptoSignalBot/1.0"
        })

    def _get(self, endpoint, params=None):
        response = self.session.get(
            self.BASE_URL + endpoint,
            params=params,
            timeout=20
        )
        response.raise_for_status()
        return response.json()

    def server_time(self):
        return self._get("/api/v1/time")

    def get_price(self, symbol="BTCUSDT"):
        return self._get(
            "/quote/v1/ticker/price",
            {"symbol": symbol}
        )

    def get_klines(self, symbol="BTCUSDT",
                   interval="5m", limit=200):

        if interval not in self.INTERVALS:
            raise ValueError("Invalid interval")

        limit = max(60, min(int(limit), 1000))

        end_time = int(time.time() * 1000)
        start_time = (
            end_time - self.INTERVALS[interval] * limit
        )

        rows = self._get(
            "/quote/v1/klines",
            {
                "symbol": symbol,
                "interval": interval,
                "startTime": start_time,
                "endTime": end_time,
                "limit": limit
            }
        )

        candles = []

        for row in rows:
            if len(row) < 6:
                continue

            candles.append({
                "open_time": int(row[0]),
                "open": float(row[1]),
                "high": float(row[2]),
                "low": float(row[3]),
                "close": float(row[4]),
                "volume": float(row[5])
            })

        return candles

    def get_multi_timeframe(self, symbol="BTCUSDT"):
        return {
            "5m": self.get_klines(symbol, "5m", 200),
            "15m": self.get_klines(symbol, "15m", 200),
            "1h": self.get_klines(symbol, "1h", 200)
        }


if __name__ == "__main__":
    api = ToobitAPI()

    try:
        data = api.get_multi_timeframe("BTCUSDT")

        for timeframe, candles in data.items():
            print(timeframe, ":", len(candles), "candles")

        print("Latest price:", api.get_price("BTCUSDT"))

    except Exception as error:
        print("Connection error:", error)
