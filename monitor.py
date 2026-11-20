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
        return
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


def _analysis_reasons(result):
    parts = []
    for tf in ("5m", "15m", "30m"):
        report = result.get("timeframes", {}).get(tf, {})
        if not report:
            continue
        parts.append(f"{tf}: bias={report.get('bias', 'unknown')}, score={report.get('score', 'n/a')}")
        families = report.get("family_scores", {})
        if families:
            parts.append("families=" + ", ".join(f"{k}:{v}" for k, v in families.items()))
        votes = report.get("indicator_votes", {})
        active = [f"{k}:{'+' if v > 0 else ''}{v}" for k, v in votes.items() if v]
        if active:
            parts.append("votes=" + ", ".join(active))
    return "؛ ".join(parts) or result.get("reason", "اجماع شاخص‌ها")


def main():
    api = ToobitAPI()
    symbols = api.get_futures_symbols(limit=MAX_SYMBOLS, preferred_bases=MAJOR_BASE_ASSETS)
    logging.info("Scanning %d supported major-coin USDT perpetual contracts: %s", len(symbols), ", ".join(symbols))

    checked = 0
    alerts = 0
    for symbol in symbols:
        try:
            candles = api.get_multi_timeframe(symbol)
            result = analyze_market(candles)
            signal = result.get("signal", "NO_SIGNAL")
            reports = result.get("timeframes", {})
            five = reports.get("5m", {})
            price = result.get("entry") or five.get("close")
            checked += 1

            logging.info(
                "%s | price=%s | signal=%s | combined_score=%s | confidence=%s",
                symbol, price, signal, result.get("combined_score"), result.get("confidence_score"),
            )
            if signal not in ("LONG", "SHORT"):
                continue
            if not price or float(price) <= 0:
                logging.warning("%s: missing price; alert skipped", symbol)
                continue

            indicators = five.get("indicators", {})
            atr = float(indicators.get("atr") or 0)
            risk = max(atr * 1.5, float(price) * 0.001)
            stop = result.get("stop_loss")
            targets = result.get("take_profits") or []
            if stop is None:
                stop = float(price) - risk if signal == "LONG" else float(price) + risk
            if len(targets) < 2:
                sign = 1 if signal == "LONG" else -1
                targets = [float(price) + sign * risk, float(price) + sign * 2 * risk]

            signal_fa = "خرید (لانگ)" if signal == "LONG" else "فروش (شورت)"
            score = result.get("confidence_score", result.get("combined_score", "نامشخص"))
            reasons = _analysis_reasons(result)
            message = (
                f"جفت‌ارز: {symbol}\n"
                f"نوع سیگنال: {signal_fa}\n"
                f"قیمت ورود پیشنهادی: {float(price):.8g}\n"
                f"حد ضرر: {float(stop):.8g}\n"
                f"حد سود ۱: {float(targets[0]):.8g}\n"
                f"حد سود ۲: {float(targets[1]):.8g}\n"
                f"امتیاز اطمینان مدل: {score}\n"
                f"دلایل: {reasons}\n"
                "فقط سیگنال است؛ هیچ سفارشی ثبت نشده است."
            )
            notify(f"سیگنال {signal_fa} | {symbol}", message, "high")
            alerts += 1
        except Exception:
            logging.exception("Analysis failed for %s; continuing with next contract", symbol)

    logging.info("Scan complete: %d contracts checked, %d alerts sent", checked, alerts)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(message)s")
    main()
