"""Regression checks ensuring expectancy no longer gates or stores signal decisions."""
import pathlib
import unittest


class ExpectancyRemovalTests(unittest.TestCase):
    def test_expectancy_policy_is_absent_from_signal_engines(self):
        root = pathlib.Path(__file__).parent
        py = (root / "signal_engine.py").read_text()
        kt = (root / "app/src/main/java/com/cryptoscalp/app/TradingStrategy.kt").read_text()
        service = (root / "app/src/main/java/com/cryptoscalp/app/CryptoMonitorService.kt").read_text()
        for obsolete in ("expectancy_allows", "expectancy_stats", "research_warmup", "net_expectancy_gate"):
            self.assertNotIn(obsolete, py)
        for obsolete in ("ExpectancyPolicy", "expectancyStats", "enforceExpectancy"):
            self.assertNotIn(obsolete, kt)
        for obsolete in ("loadExpectancyStats", "expectancyKey", "shadow_closed_net_r_"):
            self.assertNotIn(obsolete, service)


if __name__ == "__main__":
    unittest.main()
