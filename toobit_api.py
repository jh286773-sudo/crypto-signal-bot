import time
import os
import hmac
import hashlib
from urllib.parse import urlencode
import requests


class ToobitAPI:
    BASE_URL = "https://api.toobit.com"

    INTERVALS = {
        "1m": 60 * 1000, "3m": 3 * 60 * 1000,
        "5m": 5 * 60 * 1000, "15m": 15 * 60 * 1000,
        "30m": 30 * 60 * 1000, "1h": 60 * 60 * 1000,
        "4h": 4 * 60 * 60 * 1000,
    }
    DEFAULT_TIMEFRAMES = ("5m", "15m", "30m")

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
        # TradFi stock/index/commodity/FX contracts also use USDT linear swaps.
        # Restrict this research universe to underlyings listed as exchange coins.
        crypto_assets = set()
        for coin in info.get("coins", []):
            if not isinstance(coin, dict):
                continue
            for key in ("coinId", "coin", "asset", "symbol", "coinName"):
                value = coin.get(key)
                if isinstance(value, str) and value.strip():
                    crypto_assets.add(value.strip().upper())
        # A missing coin catalogue is not permission to include unknown TradFi underlyings.
        if not crypto_assets:
            raise RuntimeError("Toobit exchangeInfo did not expose a crypto coin universe")
        eligible = {
            item["symbol"]
            for item in contracts
            if item.get("status") == "TRADING"
            and item.get("quoteAsset") == "USDT"
            and item.get("inverse") is False
            and item.get("symbol", "").endswith("-SWAP-USDT")
            and str(item.get("underlying", "")).upper() in crypto_assets
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

        # Keep every eligible exchangeInfo contract in the candidate universe,
        # even if the 24h ticker endpoint omits a zero-volume/newly-listed symbol.
        ranked_symbols = {symbol for symbol, _ in ranked}
        ranked.extend((symbol, 0.0) for symbol in eligible if symbol not in ranked_symbols)
        ranked.sort(key=lambda item: item[1], reverse=True)
        return [symbol for symbol, _ in ranked[:limit]]

    def get_price(self, symbol="BTC-SWAP-USDT"):
        return self._get("/quote/v1/contract/ticker/price", {"symbol": symbol})

    def get_klines(self, symbol, interval="5m", limit=200, as_of_ms=None):
        if interval not in self.INTERVALS:
            raise ValueError("Invalid interval")

        limit = max(60, min(int(limit), 1000))
        # Use exchange server time, not the host clock, to decide whether a candle is closed.
        end_time = int(as_of_ms) if as_of_ms is not None else int(self.server_time()["serverTime"])
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
            if len(row) > 9:
                candles[-1]["taker_buy_volume"] = float(row[9])
        # Exchange kline endpoints may include the currently-forming candle.
        # Never pass that candle into indicator or signal calculations.
        candles.sort(key=lambda item: item["open_time"])
        return self.closed_candles(candles, interval, end_time)

    @classmethod
    def closed_candles(cls, candles, interval, as_of_ms):
        duration = cls.INTERVALS[interval]
        return [row for row in candles if int(row["open_time"]) + duration <= int(as_of_ms)]

    def get_multi_timeframe(self, symbol, selected_timeframes=None):
        """Fetch only selected intervals and freeze them at one common closed-candle boundary."""
        selected = tuple(dict.fromkeys(self.DEFAULT_TIMEFRAMES if selected_timeframes is None else selected_timeframes))
        if not selected or any(tf not in self.INTERVALS for tf in selected):
            raise ValueError("Invalid or empty selected timeframe list")
        server_now = int(self.server_time()["serverTime"])
        slowest_duration = max(self.INTERVALS[tf] for tf in selected)
        series = {
            tf: self.get_klines(
                symbol, tf,
                min(1000, max(260, slowest_duration // self.INTERVALS[tf] + 220)),
                as_of_ms=server_now
            )
            for tf in selected
        }
        if any(not rows for rows in series.values()):
            return {tf: [] for tf in selected}
        # The slowest available close is the common decision boundary. No
        # timeframe may contribute a candle that had not closed at that instant.
        decision_time = min(int(rows[-1]["open_time"]) + self.INTERVALS[tf]
                            for tf, rows in series.items())
        aligned = {}
        for tf, rows in series.items():
            closed = self.closed_candles(rows, tf, decision_time)
            duration = self.INTERVALS[tf]
            expected_open = (decision_time // duration) * duration - duration
            aligned[tf] = closed if closed and int(closed[-1]["open_time"]) == expected_open else []
        return aligned

    def get_klines_range(self, symbol, interval, start_ms, end_ms, limit=1000, allow_leading_gap=False):
        """Download a complete contiguous kline range by paging forward from its start."""
        if interval not in self.INTERVALS:
            raise ValueError("Invalid interval")
        step = self.INTERVALS[interval]
        start_ms, end_ms = int(start_ms), int(end_ms)
        if start_ms < 0 or end_ms <= start_ms:
            raise ValueError("Invalid historical time range")
        page_size = max(1, min(int(limit), 1000))
        expected_first = (start_ms // step) * step
        expected_last = ((end_ms - 1) // step) * step
        cursor_start = expected_first
        rows_by_time = {}
        page_number = 0
        while cursor_start <= expected_last:
            # Toobit may return only the newest 1000 rows in a wide range.
            # Bound each request to one page-sized time slice so older candles
            # cannot be displaced by newer rows from the same response.
            page_end = min(expected_last + step, cursor_start + page_size * step)
            page = self._get("/quote/v1/klines", {
                "symbol": symbol, "interval": interval, "startTime": max(0, cursor_start - 1),
                "endTime": page_end, "limit": page_size
            })
            page_number += 1
            if not isinstance(page, list) or not page:
                print(
                    f"Toobit kline page {page_number} {symbol} {interval}: "
                    f"empty; requested [{cursor_start}, {page_end})",
                    flush=True,
                )
                break
            page_opens = []
            for row in page:
                if len(row) < 6:
                    continue
                opened = int(row[0])
                if opened < cursor_start or opened > expected_last:
                    continue
                page_opens.append(opened)
                item = {
                    "timestamp": opened, "open_time": opened,
                    "open": float(row[1]), "high": float(row[2]),
                    "low": float(row[3]), "close": float(row[4]),
                    "volume": float(row[5])
                }
                if len(row) > 9:
                    item["taker_buy_volume"] = float(row[9])
                rows_by_time[opened] = item
            if not page_opens:
                print(
                    f"Toobit kline page {page_number} {symbol} {interval}: "
                    f"no valid timestamps; requested [{cursor_start}, {page_end})",
                    flush=True,
                )
                break
            oldest_open, newest_open = min(page_opens), max(page_opens)
            print(
                f"Toobit kline page {page_number} {symbol} {interval}: "
                f"rows={len(page_opens)} first={oldest_open} last={newest_open} "
                f"requested=[{cursor_start}, {page_end})",
                flush=True,
            )
            if newest_open >= expected_last:
                break
            next_cursor_start = newest_open + step
            if next_cursor_start <= cursor_start:
                raise RuntimeError("Toobit kline pagination did not move forward")
            cursor_start = next_cursor_start
            time.sleep(0.05)

        # A single bounded query attempts to recover an omitted leading prefix.
        # Some contracts genuinely have no retrievable candles at the requested
        # boundary. Historical backtests may explicitly accept that shorter
        # effective start, but never fabricate bars or tolerate gaps after it.
        if rows_by_time:
            first_received = min(rows_by_time)
            if first_received > expected_first:
                prefix_page = self._get("/quote/v1/klines", {
                    "symbol": symbol, "interval": interval,
                    "startTime": max(0, expected_first - step),
                    "endTime": first_received, "limit": page_size
                })
                if isinstance(prefix_page, list):
                    for row in prefix_page:
                        if len(row) < 6:
                            continue
                        opened = int(row[0])
                        if expected_first <= opened < first_received:
                            item = {
                                "timestamp": opened, "open_time": opened,
                                "open": float(row[1]), "high": float(row[2]),
                                "low": float(row[3]), "close": float(row[4]),
                                "volume": float(row[5])
                            }
                            if len(row) > 9:
                                item["taker_buy_volume"] = float(row[9])
                            rows_by_time[opened] = item
            effective_first = min(rows_by_time)
            if effective_first > expected_first:
                if not allow_leading_gap:
                    raise RuntimeError(
                        f"Toobit returned incomplete {interval} history: expected from "
                        f"{expected_first}, earliest retrievable candle {effective_first}"
                    )
                print(
                    f"Toobit history boundary adjusted for {symbol} {interval}: "
                    f"requested={expected_first}, first_retrievable={effective_first}; "
                    "all subsequent candles must remain contiguous",
                    flush=True,
                )

        # Retry small internal gaps with exact exchange queries. Never synthesize bars.
        ordered_times = sorted(rows_by_time)
        missing_opens = []
        for left, right in zip(ordered_times, ordered_times[1:]):
            missing_opens.extend(range(left + step, right, step))
        if len(missing_opens) > 50:
            raise RuntimeError(
                f"Toobit returned too many missing {interval} candles ({len(missing_opens)}); "
                "refusing to synthesize history"
            )
        for missing_open in missing_opens:
            for _ in range(3):
                page = self._get("/quote/v1/klines", {
                    "symbol": symbol, "interval": interval,
                    "startTime": missing_open, "endTime": missing_open + step, "limit": 10
                })
                match = next((row for row in page if len(row) >= 6
                              and int(row[0]) == missing_open), None) if isinstance(page, list) else None
                if match is not None:
                    item = {
                        "timestamp": missing_open, "open_time": missing_open,
                        "open": float(match[1]), "high": float(match[2]),
                        "low": float(match[3]), "close": float(match[4]),
                        "volume": float(match[5])
                    }
                    if len(match) > 9:
                        item["taker_buy_volume"] = float(match[9])
                    rows_by_time[missing_open] = item
                    break
                time.sleep(0.15)

        rows = [rows_by_time[key] for key in sorted(rows_by_time)]
        if not rows or (not allow_leading_gap and int(rows[0]["timestamp"]) != expected_first):
            raise RuntimeError(
                f"Toobit returned incomplete {interval} history: expected from "
                f"{expected_first}, earliest received {rows[0]['timestamp'] if rows else 'none'}"
            )
        if int(rows[-1]["timestamp"]) != expected_last:
            raise RuntimeError(
                f"Toobit returned incomplete {interval} history: expected through "
                f"{expected_last}, latest received {rows[-1]['timestamp']}"
            )
        for left, right in zip(rows, rows[1:]):
            if int(right["timestamp"]) - int(left["timestamp"]) != step:
                missing = int(left["timestamp"]) + step
                raise RuntimeError(
                    f"Toobit omitted real {interval} candle at {missing}; refusing to synthesize history"
                )
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

    def get_first_available_candle_open(self, symbol, interval, end_ms):
        """Walk backward through Toobit kline pages to find the true oldest closed candle.

        Some responses are capped at 1000 rows even when startTime=0 is supplied.
        A single request therefore cannot establish the beginning of exchange history.
        """
        if interval not in self.INTERVALS:
            raise ValueError("Invalid interval")
        step = self.INTERVALS[interval]
        cursor_end = int(end_ms)
        oldest = None
        page_size = 1000
        while cursor_end > 0:
            rows = self._get("/quote/v1/klines", {
                "symbol": symbol, "interval": interval,
                "startTime": 0, "endTime": cursor_end, "limit": page_size
            })
            if not isinstance(rows, list) or not rows:
                break
            page_opens = [
                int(row[0]) for row in rows if len(row) >= 6
                and int(row[0]) + step <= int(end_ms)
                and int(row[0]) < cursor_end
            ]
            if not page_opens:
                if len(rows) >= page_size:
                    raise RuntimeError(
                        f"Toobit kline history pagination did not move backward for {symbol} {interval}; "
                        "received a full page with no timestamps before the current cursor"
                    )
                break
            page_oldest = min(page_opens)
            if oldest is None or page_oldest < oldest:
                oldest = page_oldest
            else:
                raise RuntimeError(
                    f"Toobit kline history pagination did not move backward for {symbol} {interval}; "
                    "refusing to report a truncated history as complete"
                )
            if len(rows) < page_size or page_oldest <= 0:
                break
            next_cursor = page_oldest - 1
            if next_cursor >= cursor_end:
                raise RuntimeError("Toobit kline history pagination cursor did not decrease")
            cursor_end = next_cursor
            time.sleep(0.05)
        if oldest is None:
            raise RuntimeError(f"Toobit exposes no closed {interval} candles for {symbol}")
        return oldest

    def get_historical_range(self, symbol, start_ms, end_ms, include_funding=True, allow_leading_gap=False):
        """Fetch timestamped 5m/15m/30m histories and attach actual funding events."""
        start_by_tf = (start_ms if isinstance(start_ms, dict)
                       else {tf: int(start_ms) for tf in ("5m", "15m", "30m")})
        range_options = {"allow_leading_gap": True} if allow_leading_gap else {}
        data = {
            tf: self.get_klines_range(symbol, tf, int(start_by_tf[tf]), end_ms, **range_options)
            for tf in ("5m", "15m", "30m")
        }
        for tf, rows in data.items():
            if len(rows) < 60:
                raise RuntimeError(f"Insufficient real {tf} candles returned by Toobit: {len(rows)}")
        if include_funding:
            events = self.get_historical_funding_rates(symbol)
            # Ignore settlements before the first real 5m candle when an unavailable leading prefix was explicitly accepted.
            funding_start = int(five[0]["timestamp"]) if five else int(start_by_tf["5m"])
            if len(events) >= 1000 and events[0]["settle_time"] > funding_start:
                raise RuntimeError("Toobit funding history reached the 1000-event page cap; shorten the backtest window to avoid missing funding settlements")
            five = data["5m"]
            bars_by_open = {row["timestamp"]: row for row in five}
            for event in events:
                settle = event["settle_time"]
                if settle <= funding_start or settle > end_ms:
                    continue
                candle_open = ((settle - 1) // self.INTERVALS["5m"]) * self.INTERVALS["5m"]
                bar = bars_by_open.get(candle_open)
                if bar is None:
                    # A paged public kline response can occasionally omit one row.
                    # Retry the exact settlement candle before failing the backtest;
                    # never silently discard a real funding event.
                    for _ in range(3):
                        retry_rows = self.get_klines_range(
                            symbol, "5m", candle_open, candle_open + self.INTERVALS["5m"]
                        )
                        bar = next((row for row in retry_rows
                                    if int(row["timestamp"]) == candle_open), None)
                        if bar is not None:
                            bars_by_open[candle_open] = bar
                            five.append(bar)
                            five.sort(key=lambda row: row["timestamp"])
                            break
                        time.sleep(0.15)
                if bar is None:
                    raise RuntimeError(f"Missing 5m candle covering Toobit funding settlement at {settle}")
                if not bar["timestamp"] < settle <= bar["timestamp"] + self.INTERVALS["5m"]:
                    raise RuntimeError(f"Funding settlement timestamp does not align with a 5m candle: {settle}")
                bar["funding_rate"] = event["funding_rate"]
                bar["funding_settle_time"] = settle
        return data

    def get_historical_multi_timeframe(self, symbol, bars_5m=1000, bars_15m=400, bars_30m=200):
        """Fetch longer real exchange histories for walk-forward backtests."""
        server_now = int(self.server_time()["serverTime"])
        five = self.get_klines(symbol, "5m", bars_5m, as_of_ms=server_now)
        fifteen = self.get_klines(symbol, "15m", bars_15m, as_of_ms=server_now)
        thirty = self.get_klines(symbol, "30m", bars_30m, as_of_ms=server_now)
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
