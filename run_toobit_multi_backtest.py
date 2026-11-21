"""Run a shared-window Toobit walk-forward across all active USDT linear perpetuals."""
import argparse
import json
import math
from datetime import datetime, timezone

from backtest import run_backtest
from toobit_api import ToobitAPI


def finite_json(value):
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, dict):
        return {key: finite_json(item) for key, item in value.items()}
    if isinstance(value, list):
        return [finite_json(item) for item in value]
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--days", type=int, default=0, help="0 = all available history unless --start-date is set; otherwise cap lookback")
    parser.add_argument("--start-date", help="Inclusive UTC start date (YYYY-MM-DD), e.g. 2026-11-09")
    parser.add_argument("--start-datetime", help="Inclusive UTC start datetime (ISO-8601), e.g. 2026-11-09T01:00:00Z")
    parser.add_argument("--symbols", required=True, help="Comma-separated selected Toobit symbols, e.g. BTC-SWAP-USDT,ETH-SWAP-USDT")
    
    parser.add_argument("--slippage-bps", type=float, default=1.0,
                        help="Assumed adverse slippage per fill; not measured")
    parser.add_argument("--equity-per-symbol", type=float, default=10000.0)
    parser.add_argument("--risk", type=float, default=0.005)
    parser.add_argument("--output", default="toobit_multi_crypto_30day.json")
    args = parser.parse_args()
    if not 0 <= args.days <= 3650:
        raise SystemExit("--days must be between 0 and 3650")
    selected_symbols = list(dict.fromkeys(s.strip().upper() for s in args.symbols.split(",") if s.strip()))
    if not selected_symbols:
        raise SystemExit("--symbols must contain at least one symbol")
    if args.slippage_bps < 0:
        raise SystemExit("--slippage-bps cannot be negative")

    api = ToobitAPI()
    end_ms = int(api.server_time()["serverTime"])
    end_ms = (end_ms // 300000) * 300000
    requested_start_ms = ((end_ms - args.days * 86400000) // 1800000) * 1800000 if args.days else 0
    if args.start_date and args.start_datetime:
        raise SystemExit("Use only one of --start-date or --start-datetime")
    if args.start_date or args.start_datetime:
        try:
            if args.start_datetime:
                raw_start = args.start_datetime.replace("Z", "+00:00")
                parsed_start = datetime.fromisoformat(raw_start)
                if parsed_start.tzinfo is None:
                    raise ValueError("timezone is required")
                parsed_start = parsed_start.astimezone(timezone.utc)
            else:
                parsed_start = datetime.strptime(args.start_date, "%Y-%m-%d").replace(tzinfo=timezone.utc)
            requested_start_ms = int(parsed_start.timestamp() * 1000)
        except ValueError as exc:
            raise SystemExit("--start-date must use YYYY-MM-DD; --start-datetime must be ISO-8601 with timezone") from exc
        if requested_start_ms % 300000:
            raise SystemExit("Start datetime must align to a 5-minute candle boundary")
        if requested_start_ms >= end_ms:
            raise SystemExit("--start-date must be earlier than the current backtest end")
    # Only selected symbols are tested. Each timeframe starts at its own first real candle.
    active_symbols = set(api.get_futures_symbols(limit=10000, preferred_bases=None))
    invalid = [symbol for symbol in selected_symbols if symbol not in active_symbols]
    if invalid:
        raise SystemExit("Selected symbols are not active Toobit USDT perpetuals: " + ", ".join(invalid))
    available_starts = ({
        symbol: {tf: api.get_first_available_candle_open(symbol, tf, end_ms)
                 for tf in selected_timeframes}
        for symbol in selected_symbols
    } if not (args.start_date or args.start_datetime) else {})
    starts_by_symbol = {
        symbol: ({tf: ((requested_start_ms + api.INTERVALS[tf] - 1) // api.INTERVALS[tf]) * api.INTERVALS[tf]
                  for tf in selected_timeframes}
                 if (args.start_date or args.start_datetime) else
                 {tf: max(requested_start_ms, start) for tf, start in available_starts[symbol].items()})
        for symbol in selected_symbols
    }
    candidates = selected_symbols
    results, failures = [], []
    effective_starts = {}

    # All symbols use the same UTC window, fee schedule, slippage and risk model.
    for symbol in candidates:
        try:
            market = api.get_historical_range(symbol, starts_by_symbol[symbol], end_ms, include_funding=True, allow_leading_gap=True)
            effective_starts[symbol] = {
                tf: int(rows[0]["timestamp"]) for tf, rows in market.items() if rows
            }
            commission = {
                "source": "published Toobit VIP-0 futures taker fee",
                "openTakerFee": 0.0006,
                "closeTakerFee": 0.0006,
            }
            result = run_backtest(
                market,
                fee_bps_open=6.0,
                fee_bps_close=6.0,
                slippage_bps=args.slippage_bps,
                selected_timeframes=selected_timeframes,
                initial_equity=args.equity_per_symbol,
                risk_fraction=args.risk,
            )
            result.update({
                "symbol": symbol,
                "commission_source": commission["source"],
                "commission_rates": commission,
                "candle_counts": {tf: len(rows) for tf, rows in market.items()},
                "data_source": "Toobit public historical OHLCV and funding settlements",
            })
            results.append(result)
            print(json.dumps({
                "symbol": symbol, "trade_count": result["trade_count"],
                "net_return_pct": round(result["net_return_pct"], 4),
                "win_rate_pct": round(result["win_rate_pct"], 2),
                "max_drawdown_pct": round(result["max_drawdown_pct"], 4),
                "profit_factor": finite_json(result["profit_factor"]),
            }), flush=True)
        except Exception as exc:
            failures.append({"symbol": symbol, "error": str(exc)})
            print(f"SKIP {symbol}: {exc}", flush=True)

    total_start = sum(item["initial_equity"] for item in results)
    total_final = sum(item["final_equity"] for item in results)
    trades = [trade for item in results for trade in item["trades"]]
    wins = sum(1 for trade in trades if trade["net_pnl"] > 0)
    losses = sum(1 for trade in trades if trade["net_pnl"] < 0)
    gross_profit = sum(trade["net_pnl"] for trade in trades if trade["net_pnl"] > 0)
    gross_loss = sum(trade["net_pnl"] for trade in trades if trade["net_pnl"] < 0)
    pooled = {
        "symbols_tested": len(results),
        "trade_count": len(trades),
        "winning_trades": wins,
        "losing_trades": losses,
        "win_rate_pct": 100.0 * wins / len(trades) if trades else 0.0,
        "initial_capital_sum": total_start,
        "final_capital_sum": total_final,
        "net_pnl_sum": total_final - total_start,
        "net_return_pct_equal_capital_per_symbol": 100.0 * (total_final / total_start - 1.0) if total_start > 0 else 0.0,
        "gross_profit_sum": gross_profit,
        "gross_loss_sum": gross_loss,
        "profit_factor": gross_profit / abs(gross_loss) if gross_loss else (math.inf if gross_profit else 0.0),
        "max_symbol_drawdown_pct": max((item["max_drawdown_pct"] for item in results), default=0.0),
        "warning": "Pooled equal-capital summary of independent per-symbol simulations, not a single shared-margin portfolio. Max drawdown is the worst individual symbol drawdown. Failed symbols are listed separately and are not silently treated as zero-return symbols.",
    }
    report = {
        "period_utc": {
            "end": datetime.fromtimestamp(end_ms / 1000, timezone.utc).isoformat(),
            "days_requested_max": args.days or "all_available",
            "window_basis": ("requested inclusive UTC start; any unavailable leading prefix is disclosed and the effective start is the first real retrievable candle" if (args.start_datetime or args.start_date) else "each timeframe begins at its earliest retrievable real closed candle, optionally capped by lookback"),
            "requested_start_date_utc": args.start_date or None,
            "requested_start_datetime_utc": parsed_start.isoformat() if args.start_datetime else (datetime.fromtimestamp(requested_start_ms / 1000, timezone.utc).isoformat() if args.start_date else None),
            "actual_start_by_symbol_and_timeframe_utc": {
                symbol: {tf: datetime.fromtimestamp(start / 1000, timezone.utc).isoformat()
                         for tf, start in starts.items()}
                for symbol, starts in effective_starts.items()
            },
            "requested_start_coverage_complete": (all(
                start >= requested_start_ms
                for starts in effective_starts.values() for start in starts.values()
            ) if (args.start_date or args.start_datetime) and effective_starts else None),
            "timeframe_start_by_symbol_utc": {
                symbol: {tf: datetime.fromtimestamp(start / 1000, timezone.utc).isoformat()
                         for tf, start in starts.items()}
                for symbol, starts in starts_by_symbol.items()
            },
        },
        "methodology": {
            "strategy": "independent selected-timeframe closed-candle strategy; all selected intervals must agree",
            "selected_timeframes": list(selected_timeframes),
            "execution_timeframe": execution_tf,
            "symbol_selection": "only user-selected active Toobit USDT-margined linear perpetual contracts",
            "earliest_available_candle_by_symbol_and_timeframe_utc": {
                symbol: {tf: datetime.fromtimestamp(value / 1000, timezone.utc).isoformat()
                         for tf, value in starts.items()}
                for symbol, starts in available_starts.items()
            },
            "candidate_contracts": len(candidates),
            "successfully_tested_contracts": len(results),
            "universe_scope": "crypto underlyings listed in Toobit exchangeInfo coins; USDT linear perpetuals only; excludes TradFi, inverse and non-USDT contracts",
            "minimum_symbols": 1,
            "slippage_bps_per_fill": args.slippage_bps,
            "slippage_is_measured": False,
            "fee_source": "published Toobit VIP-0 taker fee, 6 bps each side",
            "risk_per_trade": args.risk,
            "starting_equity_per_symbol": args.equity_per_symbol,
            "funding": "historical Toobit settlement events",
            "same_requested_utc_start_for_all_symbols": True,
            "effective_start_may_differ_by_symbol_and_timeframe": True,
            "production_symbol_universe_changed": False,
            "full_candidate_scan_completed": len(results) + len(failures) == len(candidates),
            "selected_symbol_data_complete": len(results) == len(candidates) and not failures,
            "selected_symbol_data_contiguous_from_actual_start": len(results) == len(candidates) and not failures,
            "requested_start_coverage_complete": (all(
                start == requested_start_ms
                for starts in effective_starts.values() for start in starts.values()
            ) if (args.start_date or args.start_datetime) and effective_starts else None),
            "leading_history_policy": "Only an unavailable leading prefix may be excluded when explicitly enabled; no synthetic candles are created and all candles after the effective start must be contiguous.",
            "failed_contract_count": len(failures),
        },
        "pooled_summary": pooled,
        "per_symbol": results,
        "candidate_failures": failures,
    }
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(finite_json(report), handle, indent=2, ensure_ascii=False, allow_nan=False)
    print(json.dumps(finite_json({"period_utc": report["period_utc"],
                                  "pooled_summary": pooled,
                                  "symbols": [x["symbol"] for x in results],
                                  "failed_symbols": failures,
                                  "selected_symbol_data_complete": report["methodology"]["selected_symbol_data_complete"],
                                  "output": args.output}), indent=2), flush=True)
    if len(results) != len(candidates) or failures:
        raise RuntimeError(
            f"Selected-symbol backtest is incomplete: {len(results)}/{len(candidates)} "
            f"symbols completed, {len(failures)} failed. Detailed report saved to {args.output}."
        )


if __name__ == "__main__":
    main()
