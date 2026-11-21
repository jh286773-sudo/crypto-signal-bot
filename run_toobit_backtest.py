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
    parser.add_argument("--timeframes", default=os.getenv("SELECTED_TIMEFRAMES", "5m,15m,30m"), help="Selected intervals: 1m,3m,5m,15m,30m,1h,4h")
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--slippage-bps", type=float, required=True,
                         help="Adverse slippage assumption per fill, in basis points")
    parser.add_argument("--slippage-source", default="user-supplied slippage input; not independently measured")
    parser.add_argument("--public-vip0-fees", action="store_true",
                         help="Use published VIP-0 futures taker fee (6 bps) instead of account-specific rates")
    parser.add_argument("--equity", type=float, default=10000.0)
    parser.add_argument("--risk", type=float, default=0.005)
    parser.add_argument("--output", default="toobit_walk_forward_result.json")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.days < 2 or args.days > 180:
        raise SystemExit("--days must be between 2 and 180")
    if args.slippage_bps < 0:
        raise SystemExit("--slippage-bps cannot be negative")
    api = ToobitAPI()
    selected_timeframes = tuple(dict.fromkeys(x.strip().lower() for x in args.timeframes.split(",") if x.strip()))
    if not selected_timeframes or any(tf not in DURATION_MS for tf in selected_timeframes):
        raise SystemExit("Invalid --timeframes; supported: " + ", ".join(DURATION_MS))
    execution_tf = min(selected_timeframes, key=lambda tf: DURATION_MS[tf])
    execution_step = DURATION_MS[execution_tf]
    server_ms = int(api.server_time()["serverTime"])
    end_ms = int(os.environ["END_MS"]) if os.environ.get("END_MS") else (server_ms // execution_step) * execution_step
    start_ms = ((end_ms - args.days * 86_400_000) // execution_step) * execution_step
    starts_by_tf = {tf: ((start_ms + DURATION_MS[tf] - 1) // DURATION_MS[tf]) * DURATION_MS[tf]
                    for tf in selected_timeframes}

    # Account-specific open/close taker rates, returned as decimals by Toobit.
    if args.public_vip0_fees:
        fee_open_bps = fee_close_bps = 6.0
        commission = {"source": "published VIP-0 futures taker rate", "openTakerFee": 0.0006,
                      "closeTakerFee": 0.0006}
    else:
        commission = api.get_account_commission_rate(args.symbol)
        fee_open_bps = commission["openTakerFee"] * 10000
        fee_close_bps = commission["closeTakerFee"] * 10000
    data = api.get_historical_range(args.symbol, starts_by_tf, end_ms, include_funding=True)

    result = run_backtest(
        data,
        fee_bps_open=fee_open_bps,
        fee_bps_close=fee_close_bps,
        slippage_bps=args.slippage_bps,
        initial_equity=args.equity,
        risk_fraction=args.risk,
        selected_timeframes=selected_timeframes,
    )
    result["data_source"] = "Toobit public historical OHLCV and funding settlements"
    result["symbol"] = args.symbol
    result["period_utc"] = {
        "requested_start": datetime.fromtimestamp(start_ms / 1000, timezone.utc).isoformat(),
        "actual_start_by_timeframe": {tf: datetime.fromtimestamp(rows[0]["timestamp"] / 1000, timezone.utc).isoformat() for tf, rows in data.items() if rows},
        "end": datetime.fromtimestamp(end_ms / 1000, timezone.utc).isoformat(),
    }
    result["selected_timeframes"] = list(selected_timeframes)
    result["execution_timeframe"] = execution_tf
    result["commission_source"] = ("published Toobit VIP-0 futures taker schedule" if args.public_vip0_fees
                                   else "Toobit authenticated USER_DATA commissionRate")
    result["commission_rates"] = commission
    result["slippage_source"] = args.slippage_source
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
        "risk_fraction": result["risk_fraction"],
        "commission_rates": commission,
        "funding_mode": result["funding_mode"],
        "output": args.output,
    }, indent=2))


if __name__ == "__main__":
    main()
