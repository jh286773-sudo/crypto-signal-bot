import logging
import os
import requests

from toobit_api import ToobitAPI
from signal_engine import analyze_market

MAJOR_BASE_ASSETS = ("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "LINK", "AVAX")
MAX_SYMBOLS = min(10, max(1, int(os.getenv("MAX_SYMBOLS", "10"))))
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "").strip()
NTFY_SERVER = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip(" /")


def notify(title, message, priority="default"):
    if not NTFY_TOPIC:
        logging.warning("NTFY_TOPIC is not configured; phone notification skipped.")
        return False
    if "/" in NTFY_TOPIC or not NTFY_TOPIC.replace("-", "").replace("_", "").isalnum():
        raise ValueError("NTFY_TOPIC must be a simple topic name.")
    response = requests.post(
        f"{NTFY_SERVER}/{NTFY_TOPIC}",
        data=message.encode("utf-8"),
        headers={"Title": title, "Priority": priority, "Tags": "chart_with_upwards_trend"},
        timeout=20,
    )
    response.raise_for_status()
    logging.info("Phone notification sent: %s", title)
    return True


def _analysis_reasons(result):
    parts = []
    for tf, report in result.get("timeframes", {}).items():
        bias = report.get("bias", "unknown")
        votes = report.get("indicator_votes", {})
        active = [f"{name}:{'+' if value > 0 else ''}{value}"
                  for name, value in votes.items() if value]
        detail = f"{tf}: {bias}"
        if active:
            detail += " (" + ", ".join(active) + ")"
        parts.append(detail)
    return "؛ ".join(parts) or result.get("reason", "No directional confirmation")


def main():
    api = ToobitAPI()
    symbols = api.get_futures_symbols(limit=MAX_SYMBOLS, preferred_bases=MAJOR_BASE_ASSETS)
    logging.info("Scanning %d supported major-coin USDT perpetual contracts: %s",
                 len(symbols), ", ".join(symbols))

    checked = 0
    alerts = 0
    for symbol in symbols:
        try:
            candles = api.get_multi_timeframe(symbol)
            # The same no-score, selected-timeframe unanimous engine is used for
            # every Python monitor cycle. No expectancy/score gate is applied.
            result = analyze_market(candles)
            signal = result.get("signal", "NO_SIGNAL")
            reports = result.get("timeframes", {})
            entry_tf = result.get("entry_timeframe") or next(iter(reports), None)
            entry_report = reports.get(entry_tf, {}) if entry_tf else {}
            price = result.get("entry") or entry_report.get("close")
            checked += 1

            logging.info("%s | price=%s | signal=%s | reason=%s",
                         symbol, price, signal, result.get("reason", ""))
            if signal not in ("LONG", "SHORT"):
                continue
            if price is None or float(price) <= 0:
                logging.warning("%s: missing valid entry price; alert skipped", symbol)
                continue

            atr = float(entry_report.get("indicators", {}).get("atr") or 0.0)
            risk = max(atr * 1.5, float(price) * 0.001)
            stop = float(price) - risk if signal == "LONG" else float(price) + risk
            sign = 1 if signal == "LONG" else -1
            targets = [float(price) + sign * risk, float(price) + sign * 2 * risk]

            signal_fa = "خرید (لانگ)" if signal == "LONG" else "فروش (شورت)"
            reasons = _analysis_reasons(result)
            message = (
                f"جفت‌ارز: {symbol}\n"
                f"نوع سیگنال: {signal_fa}\n"
                f"قیمت ورود پیشنهادی: {float(price):.8g}\n"
                f"حد ضرر محاسباتی: {stop:.8g}\n"
                f"حد سود ۱: {targets[0]:.8g}\n"
                f"حد سود ۲: {targets[1]:.8g}\n"
                f"دلایل: {reasons}\n"
                "فقط سیگنال است؛ هیچ سفارشی ثبت نشده است."
            )
            if notify(f"سیگنال {signal_fa} | {symbol}", message, "high"):
                alerts += 1
        except Exception:
            logging.exception("Analysis failed for %s; continuing with next contract", symbol)

    logging.info("Scan complete: %d contracts checked, %d alerts sent", checked, alerts)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(message)s")
    main()
