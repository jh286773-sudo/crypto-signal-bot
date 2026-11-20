import unittest
from toobit_api import ToobitAPI


class ToobitClosedCandleTests(unittest.TestCase):
    def test_forming_candle_is_excluded_and_close_boundary_is_included(self):
        rows=[{"open_time":0},{"open_time":300000},{"open_time":600000}]
        self.assertEqual(ToobitAPI.closed_candles(rows,"5m",600000),[rows[0],rows[1]])
        self.assertEqual(ToobitAPI.closed_candles(rows,"5m",900000),rows)

    def test_multi_timeframe_uses_one_exchange_clock_snapshot(self):
        from unittest.mock import patch
        api = ToobitAPI()
        five = [{"open_time": 1_500_000}]
        fifteen = [{"open_time": 900_000}, {"open_time": 1_800_000}]
        thirty = [{"open_time": 0}, {"open_time": 1_800_000}]
        with patch.object(api, "server_time", return_value={"serverTime": 1_800_000}) as clock:
            with patch.object(api, "get_klines", side_effect=[five, fifteen, thirty]) as get:
                result = api.get_multi_timeframe("BTC-SWAP-USDT")
        self.assertEqual(get.call_args_list[0].kwargs["as_of_ms"], 1_800_000)
        self.assertEqual(get.call_args_list[1].kwargs["as_of_ms"], 1_800_000)
        self.assertEqual(get.call_args_list[2].kwargs["as_of_ms"], 1_800_000)
        self.assertEqual([call.args[2] for call in get.call_args_list], [260, 260, 260])
        self.assertEqual(result["15m"], fifteen[:1])
        self.assertEqual(result["30m"], thirty[:1])

    def test_higher_timeframe_is_frozen_to_5m_decision_close(self):
        fifteen=[{"open_time":0},{"open_time":900000},{"open_time":1800000}]
        thirty=[{"open_time":0},{"open_time":1800000}]
        decision=1800000
        self.assertEqual(ToobitAPI.closed_candles(fifteen,"15m",decision),fifteen[:2])
        self.assertEqual(ToobitAPI.closed_candles(thirty,"30m",decision),thirty[:1])


if __name__=="__main__":
    unittest.main()
