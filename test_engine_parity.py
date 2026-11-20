import pathlib
import unittest


class CrossEngineContractTests(unittest.TestCase):
    def test_python_and_android_share_smc_and_entry_guards(self):
        root=pathlib.Path(__file__).parent
        py=(root/"signal_engine.py").read_text()
        kt=(root/"app/src/main/java/com/cryptoscalp/app/TradingStrategy.kt").read_text()
        # Contract-level parity guard: if either implementation changes a critical
        # threshold or SMC rule, CI requires this test to be reviewed and updated.
        for token in ("atr_ref*0.50", "retest_long", "retest_short",
                      "false_breakout", "equal_high_liquidity", "equal_low_liquidity"):
            self.assertIn(token,py)
        for token in ("atrRef*.50", "retestLong", "retestShort",
                      "falseBreakout", "equalHighs", "equalLows"):
            self.assertIn(token,kt)
        self.assertIn("entry_distance_atr",py)
        self.assertIn("entryDistanceAtr",kt)
        self.assertIn(">.80",kt)
        self.assertIn("0.80",py)


if __name__ == "__main__":
    unittest.main()
