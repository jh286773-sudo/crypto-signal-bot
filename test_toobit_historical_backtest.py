import unittest
from unittest.mock import patch
from toobit_api import ToobitAPI


class ToobitHistoricalBacktestTests(unittest.TestCase):
    def test_kline_range_pages_without_duplicate_candles(self):
        api = ToobitAPI()
        first = [[i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
                 for i in range(1000)]
        second = [[i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
                  for i in range(999, 1002)]
        with patch.object(api, "_get", side_effect=[first, second]) as get:
            rows = api.get_klines_range("BTC-SWAP-USDT", "5m", 0, 1002 * 300000)
        self.assertEqual(len(rows), 1001)
        self.assertEqual(rows[0]["timestamp"], 0)
        self.assertEqual(rows[-1]["timestamp"], 1000 * 300000)
        self.assertEqual(rows[0]["taker_buy_volume"], 4.0)
        self.assertEqual(get.call_count, 2)

    def test_account_fee_rate_uses_signed_read_only_endpoint(self):
        api = ToobitAPI(api_key="test-key", api_secret="test-secret")
        with patch.object(api, "_signed_get", return_value={
            "openMakerFee": "0.00002", "openTakerFee": "0.0006",
            "closeMakerFee": "0.00002", "closeTakerFee": "0.0006"
        }) as signed:
            fees = api.get_account_commission_rate("BTC-SWAP-USDT")
        signed.assert_called_once_with("/api/v1/futures/commissionRate", {"symbol": "BTC-SWAP-USDT"})
        self.assertAlmostEqual(fees["openTakerFee"], 0.0006)

    def test_funding_history_is_parsed_and_sorted(self):
        api = ToobitAPI()
        payload = [
            {"settleTime": "1600", "settleRate": "0.001"},
            {"settleTime": "800", "settleRate": "-0.002"}
        ]
        with patch.object(api, "_get", return_value=payload):
            rates = api.get_historical_funding_rates("BTC-SWAP-USDT")
        self.assertEqual([x["settle_time"] for x in rates], [800, 1600])


if __name__ == "__main__":
    unittest.main()
