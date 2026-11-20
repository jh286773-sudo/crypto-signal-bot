import time
import requests


class ToobitAPI:
    BASE_URL = "https://api.toobit.com"

    INTERVALS = {
        "5m": 5 * 60 * 1000,
        "15m": 15 * 60 * 1000,
        "30m": 30 * 60 * 1000,
    }

    def __init__(self):
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": "CryptoSignalBot/1.0"})

    def _get(self, endpoint, params=None):
        response = self.session.get(
            self.BASE_URL + endpoint, params=params, timeout=20
        )
        response.raise_for_status()
        return response.json()

    def server_time(self):
        return self._get("/api/v1/time")

    def get_futures_symbols(self, limit=10, preferred_bases=None):
        """Return liquid active USDT perpetuals, optionally limited to preferred base assets."""
        info = self._get("/api/v1/exchangeInfo")
        contracts = info.get("contracts", [])
        eligible = {
            item["symbol"]
            for item in contracts
            if item.get("status") == "TRADING"
            and item.get("quoteAsset") == "USDT"
            and item.get("inverse") is False
            and item.get("symbol", "").endswith("-SWAP-USDT")
        }

        if preferred_bases:
            preferred = tuple(f"{base}-" for base in preferred_bases)
            eligible = {symbol for symbol in eligible if symbol.startswith(preferred)}

        if not eligible:
            raise RuntimeError("No active USDT-margined perpetuals found for the requested assets")

        tickers = self._get("/quote/v1/contract/ticker/24hr")
        ranked = []
        for ticker in tickers if isinstance(tickers, list) else []:
            symbol = ticker.get("s")
            if symbol in eligible:
                try:
                    ranked.append((symbol, float(ticker.get("qv", 0))))
                except (TypeError, ValueError):
                    ranked.append((symbol, 0.0))

        ranked.sort(key=lambda item: item[1], reverse=True)
        symbols = [symbol for symbol, _ in ranked[:limit]]
        if not symbols:
            symbols = sorted(eligible)[:limit]
        return symbols

    def get_price(self, symbol="BTC-SWAP-USDT"):
        return self._get("/quote/v1/contract/ticker/price", {"symbol": symbol})

    def get_klines(self, symbol, interval="5m", limit=200):
        if interval not in self.INTERVALS:
            raise ValueError("Invalid interval")

        limit = max(60, min(int(limit), 1000))
        end_time = int(time.time() * 1000)
        start_time = end_time - self.INTERVALS[interval] * limit

        rows = self._get(
            "/quote/v1/klines",
            {
                "symbol": symbol,
                "interval": interval,
                "startTime": start_time,
                "endTime": end_time,
                "limit": limit,
            },
        )

        candles = []
        for row in rows:
            if len(row) < 6:
                continue
            candles.append(
                {
                    "open_time": int(row[0]),
                    "open": float(row[1]),
                    "high": float(row[2]),
                    "low": float(row[3]),
                    "close": float(row[4]),
                    "volume": float(row[5]),
                }
            )
        # Exchange kline endpoints may include the currently-forming candle.
        # Never pass that candle into indicator or signal calculations.
        candles.sort(key=lambda item: item["open_time"])
        return self.closed_candles(candles, interval, end_time)

    @classmethod
    def closed_candles(cls, candles, interval, as_of_ms):
        duration = cls.INTERVALS[interval]
        return [row for row in candles if int(row["open_time"]) + duration <= int(as_of_ms)]

    def get_multi_timeframe(self, symbol):
        five = self.get_klines(symbol, "5m", 200)
        fifteen = self.get_klines(symbol, "15m", 200)
        thirty = self.get_klines(symbol, "30m", 200)
        if not five:
            return {"5m": [], "15m": [], "30m": []}
        # Freeze all series to the same 5m decision close. A higher-timeframe
        # bar closing during subsequent HTTP requests is still future data.
        decision_time = int(five[-1]["open_time"]) + self.INTERVALS["5m"]
        return {
            "5m": five,
            "15m": self.closed_candles(fifteen, "15m", decision_time),
            "30m": self.closed_candles(thirty, "30m", decision_time),
        }


if __name__ == "__main__":
    api = ToobitAPI()
    try:
        symbols = api.get_futures_symbols()
        print("Active USDT perpetuals selected:", len(symbols))
        print(", ".join(symbols))
        if symbols:
            data = api.get_multi_timeframe(symbols[0])
            for timeframe, candles in data.items():
                print(timeframe, ":", len(candles), "candles")
    except Exception as error:
        print("Connection error:", error)
