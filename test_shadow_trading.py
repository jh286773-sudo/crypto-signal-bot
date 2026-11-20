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

    @staticmethod
    def candidate(side="LONG", entry=100):
        return {"signal": side, "entry": entry}

    def open_state(self, symbol="BTC-SWAP-USDT"):
        with self.ledger._db() as db:
            row = db.execute("SELECT state FROM shadow_open WHERE symbol=?", (symbol,)).fetchone()
        return None if row is None else __import__("json").loads(row[0])

    def test_long_is_held_through_price_moves_and_wait(self):
        self.ledger.process("BTC-SWAP-USDT", self.candle(300000, 100, 130, 70, 110), self.candidate("LONG"))
        self.ledger.process("BTC-SWAP-USDT", self.candle(600000, 110, 150, 60, 90), {"signal": "NO_SIGNAL"})
        self.ledger.process("BTC-SWAP-USDT", self.candle(900000, 90, 140, 50, 120), {"signal": "WAIT"})
        self.assertEqual(self.open_state()["side"], "LONG")
        self.assertEqual(self.ledger.stats("BTC-SWAP-USDT")["LONG"], [])

    def test_long_reverses_to_short_on_opposite_signal(self):
        self.ledger.process("BTC-SWAP-USDT", self.candle(300000, 100, 101, 99, 100), self.candidate("LONG", 100))
        self.ledger.process("BTC-SWAP-USDT", self.candle(600000, 105, 110, 104, 108), self.candidate("SHORT", 108))
        state = self.open_state()
        self.assertEqual(state["side"], "SHORT")
        self.assertEqual(state["entry"], 108)
        self.assertEqual(len(self.ledger.stats("BTC-SWAP-USDT")["LONG"]), 1)
        self.assertGreater(self.ledger.stats("BTC-SWAP-USDT")["LONG"][0], 0)

    def test_short_reverses_to_long_on_opposite_signal(self):
        self.ledger.process("BTC-SWAP-USDT", self.candle(300000, 100, 101, 99, 100), self.candidate("SHORT", 100))
        self.ledger.process("BTC-SWAP-USDT", self.candle(600000, 95, 96, 90, 92), self.candidate("LONG", 92))
        state = self.open_state()
        self.assertEqual(state["side"], "LONG")
        self.assertEqual(state["entry"], 92)
        self.assertEqual(len(self.ledger.stats("BTC-SWAP-USDT")["SHORT"]), 1)
        self.assertGreater(self.ledger.stats("BTC-SWAP-USDT")["SHORT"][0], 0)

    def test_same_side_signal_does_not_duplicate_or_reset_position(self):
        self.ledger.process("BTC-SWAP-USDT", self.candle(300000, 100, 101, 99, 100), self.candidate("LONG", 100))
        self.ledger.process("BTC-SWAP-USDT", self.candle(600000, 110, 111, 109, 110), self.candidate("LONG", 110))
        self.assertEqual(self.open_state()["entry"], 100)
        with self.ledger._db() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM shadow_open").fetchone()[0], 1)

    def test_duplicate_candle_does_not_duplicate_entry_or_exit(self):
        bar = self.candle(300000, 100, 101, 99, 100)
        self.ledger.process("BTC-SWAP-USDT", bar, self.candidate())
        self.ledger.process("BTC-SWAP-USDT", bar, self.candidate())
        with self.ledger._db() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM shadow_open").fetchone()[0], 1)


if __name__ == "__main__":
    unittest.main()
