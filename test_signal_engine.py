import unittest, math
from unittest.mock import patch
from signal_engine import analyze_timeframe, analyze_market

def candles(n=100, trend=1.0):
    rows=[]; p=100.0
    for i in range(n):
        p += trend*0.12 + math.sin(i/3)*0.03
        rows.append({"open":p-0.02,"high":p+0.15,"low":p-0.15,"close":p,"volume":100+i})
    return rows

class EngineTests(unittest.TestCase):
    def test_indicator_report(self):
        r=analyze_timeframe(candles())
        self.assertTrue(r["valid"])
        for key in ("ema","sma","rsi","macd","bollinger","stoch_rsi","adx_dmi","ichimoku","atr","vwap","obv","mfi","divergence","smc","taker_flow"):
            if key=="ema" or key=="sma": self.assertIn(key,r["indicators"])
            else: self.assertIn(key,r["indicators"])
    def test_requires_all_timeframes(self):
        r=analyze_market({"5m":candles(),"15m":candles()})
        self.assertEqual(r["signal"],"NO_SIGNAL")
    def test_signal_shape(self):
        data={tf:candles(120,1.0) for tf in ("5m","15m","30m")}
        r=analyze_market(data)
        self.assertIn(r["signal"],("LONG","SHORT","NO_SIGNAL"))
        self.assertIn("combined_score",r)
    def test_two_of_three_alignment_never_signals(self):
        bullish={"valid":True,"score":60.0,"bias":"bullish","indicators":{"atr":1.0},"close":100.0}
        bearish={"valid":True,"score":-60.0,"bias":"bearish","indicators":{"atr":1.0},"close":100.0}
        with patch("signal_engine.analyze_timeframe", side_effect=[bullish,bullish,bearish]):
            result=analyze_market({tf:[] for tf in ("5m","15m","30m")})
        self.assertEqual(result["signal"],"NO_SIGNAL")
    def test_all_three_aligned_can_signal(self):
        bullish={"valid":True,"score":60.0,"bias":"bullish","indicators":{"atr":1.0},"close":100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result=analyze_market({tf:[] for tf in ("5m","15m","30m")})
        self.assertEqual(result["signal"],"LONG")
    def test_bad_input_safe(self):
        self.assertFalse(analyze_timeframe([{"close":1}]*70)["valid"])

if __name__=="__main__": unittest.main()
