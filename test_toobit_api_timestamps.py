import unittest
from toobit_api import ToobitAPI


class ToobitClosedCandleTests(unittest.TestCase):
    def test_forming_candle_is_excluded_and_close_boundary_is_included(self):
        rows=[{"open_time":0},{"open_time":300000},{"open_time":600000}]
        self.assertEqual(ToobitAPI.closed_candles(rows,"5m",600000),[rows[0],rows[1]])
        self.assertEqual(ToobitAPI.closed_candles(rows,"5m",900000),rows)

    def test_higher_timeframe_is_frozen_to_5m_decision_close(self):
        fifteen=[{"open_time":0},{"open_time":900000},{"open_time":1800000}]
        thirty=[{"open_time":0},{"open_time":1800000}]
        decision=1800000
        self.assertEqual(ToobitAPI.closed_candles(fifteen,"15m",decision),fifteen[:2])
        self.assertEqual(ToobitAPI.closed_candles(thirty,"30m",decision),thirty[:1])


if __name__=="__main__":
    unittest.main()
