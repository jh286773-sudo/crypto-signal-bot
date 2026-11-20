import tempfile
import unittest
from pathlib import Path

from shadow_trading import ShadowLedger


class ShadowLedgerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.ledger = ShadowLedger(str(Path(self.temp.name) / "shadow.sqlite3"))

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def candle(ts, open_, high, low, close):
        return {"timestamp": ts, "open": open_, "high": high, "low": low, "close": close, "volume": 1}

    def candidate(self, side="LONG"):
        if side == "LONG":
            return {"signal": "LONG", "entry": 100, "stop_loss": 90, "take_profits": [115, 125, 135]}
        return {"signal": "SHORT", "entry": 100, "stop_loss": 110, "take_profits": [85, 75, 65]}

    def test_shadow_trade_warms_up_and_records_net_r_after_tp1_then_breakeven(self):
        self.ledger.process("BTC-SWAP-USDT", self.candle(300000, 100, 105, 99, 102), self.candidate())
        self.assertEqual(self.ledger.stats("BTC-SWAP-USDT")["LONG"], [])
        self.ledger.process("BTC-SWAP-USDT", self.candle(600000, 102, 116, 101, 114), {"signal": "NO_SIGNAL"})
        self.ledger.process("BTC-SWAP-USDT", self.candle(900000, 102, 110, 99, 100), {"signal": "NO_SIGNAL"})
        values = self.ledger.stats("BTC-SWAP-USDT")["LONG"]
        self.assertEqual(len(values), 1)
        self.assertGreater(values[0], 0.0)
        self.assertEqual(self.ledger.stats("ETH-SWAP-USDT")["LONG"], [])

    def test_ambiguous_stop_and_target_candle_resolves_to_stop(self):
        self.ledger.process("BTC-SWAP-USDT", self.candle(300000, 100, 116, 89, 100), self.candidate())
        values = self.ledger.stats("BTC-SWAP-USDT")["LONG"]
        self.assertEqual(len(values), 1)
        self.assertLess(values[0], 0.0)

    def test_long_and_short_expectancy_are_separate(self):
        self.ledger.process("BTC-SWAP-USDT", self.candle(300000, 100, 101, 99, 100), self.candidate("LONG"))
        self.ledger.process("BTC-SWAP-USDT", self.candle(600000, 100, 111, 99, 110), {"signal": "NO_SIGNAL"})
        self.assertEqual(len(self.ledger.stats("BTC-SWAP-USDT")["LONG"]), 1)
        self.assertEqual(self.ledger.stats("BTC-SWAP-USDT")["SHORT"], [])

    def test_duplicate_candle_does_not_duplicate_entry_or_exit(self):
        bar = self.candle(300000, 100, 101, 99, 100)
        self.ledger.process("BTC-SWAP-USDT", bar, self.candidate())
        self.ledger.process("BTC-SWAP-USDT", bar, self.candidate())
        with self.ledger._db() as db:
            count = db.execute("SELECT COUNT(*) FROM shadow_open").fetchone()[0]
        self.assertEqual(count, 1)


if __name__ == "__main__":
    unittest.main()
