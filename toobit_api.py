import time
import os
import hmac
import hashlib
from urllib.parse import urlencode
import requests


class ToobitAPI:
    BASE_URL = "https://api.toobit.com"

    INTERVALS = {
        "5m": 5 * 60 * 1000,
        "15m": 15 * 60 * 1000,
        "30m": 30 * 60 * 1000,
    }

    def __init__(self, api_key=None, api_secret=None):
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": "CryptoSignalBot/1.0"})
        self.api_key = api_key or os.getenv("TOOBIT_API_KEY")
        self.api_secret = api_secret or os.getenv("TOOBIT_API_SECRET")

    def _signed_get(self, endpoint, params):
        """Read-only USER_DATA request. Never logs or persists API credentials."""
        if not self.api_key or not self.api_secret:
            raise RuntimeError("TOOBIT_API_KEY and TOOBIT_API_SECRET are required for account fee lookup")
        server_ms = int(self.server_time()["serverTime"])
        ordered = list(params.items()) + [("timestamp", server_ms), ("recvWindow", 5000)]
        query = urlencode(ordered)
        signature = hmac.new(self.api_secret.encode(), query.encode(), hashlib.sha256).hexdigest()
        response = self.session.get(
            self.BASE_URL + endpoint + "?" + query + "&signature=" + signature,
            headers={"X-BB-APIKEY": self.api_key}, timeout=20
        )
        response.raise_for_status()
        return response.json()

    def get_account_commission_rate(self, symbol):
        """Fetch the authenticated account's actual futures maker/taker fee schedule."""
        data = self._signed_get("/api/v1/futures/commissionRate", {"symbol": symbol})
        required = ("openMakerFee", "openTakerFee", "closeMakerFee", "closeTakerFee")
        if not isinstance(data, dict) or any(data.get(key) is None for key in required):
            raise RuntimeError("Toobit commissionRate response is missing expected fee fields")
        return {key: float(data[key]) for key in required}

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

    def get_klines_range(self, symbol, interval, start_ms, end_ms, limit=1000):
        """Download a complete contiguous kline range, paging at Toobit's 1000-bar cap."""
        if interval not in self.INTERVALS:
            raise ValueError("Invalid interval")
        step = self.INTERVALS[interval]
        start_ms, end_ms = int(start_ms), int(end_ms)
        if start_ms < 0 or end_ms <= start_ms:
            raise ValueError("Invalid historical time range")
        page_size = max(1, min(int(limit), 1000))
        cursor = (start_ms // step) * step
        rows_by_time = {}
        while cursor < end_ms:
            page = self._get("/quote/v1/klines", {
                "symbol": symbol, "interval": interval, "startTime": cursor,
                "endTime": end_ms, "limit": page_size
            })
            if not page:
                break
            last_open = None
            for row in page:
                if len(row) < 6:
                    continue
                opened = int(row[0])
                if opened < start_ms or opened >= end_ms:
                    continue
                last_open = opened if last_open is None else max(last_open, opened)
                item = {
                    "timestamp": opened, "open_time": opened,
                    "open": float(row[1]), "high": float(row[2]),
                    "low": float(row[3]), "close": float(row[4]),
                    "volume": float(row[5])
                }
                if len(row) > 9:
                    item["taker_buy_volume"] = float(row[9])
                rows_by_time[opened] = item
            if last_open is None or last_open < cursor:
                break
            next_cursor = last_open + step
            if next_cursor <= cursor:
                raise RuntimeError("Toobit kline pagination did not advance")
            cursor = next_cursor
            if len(page) < page_size:
                break
            time.sleep(0.05)
        rows = [rows_by_time[key] for key in sorted(rows_by_time)]
        return self.closed_candles(rows, interval, end_ms)

    def get_historical_funding_rates(self, symbol, limit=1000):
        """Read historical funding settlement rates from Toobit public market data."""
        rows = self._get("/api/v1/futures/historyFundingRate", {
            "symbol": symbol, "limit": max(1, min(int(limit), 1000))
        })
        parsed = []
        for row in rows if isinstance(rows, list) else []:
            if row.get("settleTime") is None or row.get("settleRate") is None:
                continue
            parsed.append({
                "settle_time": int(row["settleTime"]),
                "funding_rate": float(row["settleRate"])
            })
        return sorted(parsed, key=lambda x: x["settle_time"])

    def get_historical_range(self, symbol, start_ms, end_ms, include_funding=True):
        """Fetch timestamped 5m/15m/30m histories and attach actual funding events."""
        data = {
            tf: self.get_klines_range(symbol, tf, start_ms, end_ms)
            for tf in ("5m", "15m", "30m")
        }
        for tf, rows in data.items():
            if len(rows) < 60:
                raise RuntimeError(f"Insufficient real {tf} candles returned by Toobit: {len(rows)}")
        if include_funding:
            events = self.get_historical_funding_rates(symbol)
            if len(events) >= 1000 and events[0]["settle_time"] > int(start_ms):
                raise RuntimeError("Toobit funding history reached the 1000-event page cap; shorten the backtest window to avoid missing funding settlements")
            five = data["5m"]
            bars_by_open = {row["timestamp"]: row for row in five}
            for event in events:
                settle = event["settle_time"]
                if settle <= start_ms or settle > end_ms:
                    continue
                candle_open = ((settle - 1) // self.INTERVALS["5m"]) * self.INTERVALS["5m"]
                bar = bars_by_open.get(candle_open)
                if bar is None:
                    raise RuntimeError(f"Missing 5m candle covering Toobit funding settlement at {settle}")
                if not bar["timestamp"] < settle <= bar["timestamp"] + self.INTERVALS["5m"]:
                    raise RuntimeError(f"Funding settlement timestamp does not align with a 5m candle: {settle}")
                bar["funding_rate"] = event["funding_rate"]
                bar["funding_settle_time"] = settle
        return data

    def get_historical_multi_timeframe(self, symbol, bars_5m=1000, bars_15m=400, bars_30m=200):
        """Fetch longer real exchange histories for walk-forward backtests."""
        five = self.get_klines(symbol, "5m", bars_5m)
        fifteen = self.get_klines(symbol, "15m", bars_15m)
        thirty = self.get_klines(symbol, "30m", bars_30m)
        if not five:
            raise RuntimeError("Toobit returned no closed 5m history")
        decision_time = int(five[-1]["open_time"]) + self.INTERVALS["5m"]
        aligned = {
            "5m": five,
            "15m": self.closed_candles(fifteen, "15m", decision_time),
            "30m": self.closed_candles(thirty, "30m", decision_time),
        }
        insufficient = [tf for tf, rows in aligned.items() if len(rows) < 60]
        if insufficient:
            raise RuntimeError("Insufficient closed historical bars for: " + ", ".join(insufficient))
        return aligned


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
