"""Run an actual Toobit historical walk-forward backtest.

Credentials are read only from environment variables and are used solely for the
read-only commission-rate endpoint. Market candles and funding history are public.
"""
import argparse
import json
import os
from datetime import datetime, timezone

from backtest import run_backtest
from toobit_api import ToobitAPI
from walk_forward import DURATION_MS


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbol", default="BTC-SWAP-USDT")
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--slippage-bps", type=float, required=True,
                         help="Measured adverse slippage per fill, in basis points")
    parser.add_argument("--equity", type=float, default=10000.0)
    parser.add_argument("--risk", type=float, default=0.01)
    parser.add_argument("--output", default="toobit_walk_forward_result.json")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.days < 2 or args.days > 180:
        raise SystemExit("--days must be between 2 and 180")
    if args.slippage_bps < 0:
        raise SystemExit("--slippage-bps cannot be negative")
    api = ToobitAPI()
    server_ms = int(api.server_time()["serverTime"])
    end_ms = (server_ms // DURATION_MS["5m"]) * DURATION_MS["5m"]
    start_ms = ((end_ms - args.days * 86_400_000) // DURATION_MS["30m"]) * DURATION_MS["30m"]

    # Account-specific open/close taker rates, returned as decimals by Toobit.
    commission = api.get_account_commission_rate(args.symbol)
    data = api.get_historical_range(args.symbol, start_ms, end_ms, include_funding=True)

    result = run_backtest(
        data,
        fee_bps_open=commission["openTakerFee"] * 10000,
        fee_bps_close=commission["closeTakerFee"] * 10000,
        slippage_bps=args.slippage_bps,
        initial_equity=args.equity,
        risk_fraction=args.risk,
    )
    result["data_source"] = "Toobit public historical OHLCV and funding settlements"
    result["symbol"] = args.symbol
    result["period_utc"] = {
        "start": datetime.fromtimestamp(start_ms / 1000, timezone.utc).isoformat(),
        "end": datetime.fromtimestamp(end_ms / 1000, timezone.utc).isoformat(),
    }
    result["commission_source"] = "Toobit authenticated USER_DATA commissionRate"
    result["commission_rates"] = commission
    result["slippage_source"] = "user-supplied measured adverse slippage per fill"
    result["candle_counts"] = {tf: len(rows) for tf, rows in data.items()}

    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2, ensure_ascii=False)
    print(json.dumps({
        "symbol": args.symbol,
        "candle_counts": result["candle_counts"],
        "trade_count": result["trade_count"],
        "net_return_pct": result["net_return_pct"],
        "win_rate_pct": result["win_rate_pct"],
        "max_drawdown_pct": result["max_drawdown_pct"],
        "commission_rates": commission,
        "funding_mode": result["funding_mode"],
        "output": args.output,
    }, indent=2))


if __name__ == "__main__":
    main()
