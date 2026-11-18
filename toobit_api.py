
import requests
from typing import Optional


class ToobitAPI:
    BASE_URL = "https://api.toobit.com"

    def __init__(self, api_key: Optional[str] = None):
        self.api_key = api_key
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": "CryptoSignalBot/1.0"
        })

    def _get(self, endpoint, params=None):
        url = self.BASE_URL + endpoint
        response = self.session.get(
            url,
            params=params,
            timeout=15
        )
        response.raise_for_status()
        return response.json()

    def server_time(self):
        return self._get("/api/v1/time")

    def exchange_info(self):
        return self._get("/api/v1/exchangeInfo")

    def get_price(self, symbol="BTCUSDT"):
        data = self._get(
            "/quote/v1/ticker/price",
            {"symbol": symbol}
        )
        return data

    def get_24h(self, symbol="BTCUSDT"):
        return self._get(
            "/quote/v1/ticker/24hr",
            {"symbol": symbol}
        )

    def get_klines(
        self,
        symbol="BTCUSDT",
        interval="5m",
        limit=200
    ):
        return self._get(
            "/quote/v1/klines",
            {
                "symbol": symbol,
                "interval": interval,
                "limit": limit
            }
        )

    def get_multi_timeframe(self, symbol="BTCUSDT"):
        return {
            "5m": self.get_klines(symbol, "5m"),
            "15m": self.get_klines(symbol, "15m"),
            "1h": self.get_klines(symbol, "1h")
        }

    def get_order_book(self, symbol="BTCUSDT"):
        return self._get(
            "/quote/v1/depth",
            {
                "symbol": symbol,
                "limit": 100
            }
        )

    def get_recent_trades(self, symbol="BTCUSDT"):
        return self._get(
            "/quote/v1/trades",
            {
                "symbol": symbol,
                "limit": 60
            }
        )


if __name__ == "__main__":
    api = ToobitAPI()

    try:
        print("Toobit connection successful")

        print("BTC Price:")
        print(api.get_price("BTCUSDT"))

        print("Multi timeframe:")
        print(api.get_multi_timeframe("BTCUSDT"))

    except requests.RequestException as error:
        print("API Error:", error)

