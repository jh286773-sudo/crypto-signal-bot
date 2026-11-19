import logging
import os
import requests

from toobit_api import ToobitAPI
from signal_engine import analyze_market

MAX_SYMBOLS = int(os.getenv("MAX_SYMBOLS", "40"))
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "").strip()
NTFY_SERVER = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip(" /")

REASON_FA = {
    "15m trend is bullish": "روند ۱۵ دقیقه‌ای صعودی است",
    "15m trend is bearish": "روند ۱۵ دقیقه‌ای نزولی است",
    "5m scalp entry confirmed": "ورود اسکالپ در تایم‌فریم ۵ دقیقه تأیید شد",
    "1h trend confirms 15m direction": "روند یک‌ساعته جهت ۱۵ دقیقه‌ای را تأیید می‌کند",
    "1h trend does not confirm; confidence reduced": "روند یک‌ساعته تأیید نمی‌کند؛ اطمینان کاهش یافته است",
    "Bullish market structure": "ساختار بازار صعودی است",
    "Bearish market structure": "ساختار بازار نزولی است",
    "Bearish market structure warning": "هشدار: ساختار بازار نزولی است",
    "Bullish market structure warning": "هشدار: ساختار بازار صعودی است",
    "Bullish liquidity sweep": "جمع‌آوری نقدینگی صعودی",
    "Bearish liquidity sweep": "جمع‌آوری نقدینگی نزولی",
    "Bullish order block": "بلوک سفارش صعودی",
    "Bearish order block": "بلوک سفارش نزولی",
}


def translate_reason(reason):
    """Translate known signal-engine reasons while preserving unknown text."""
    return REASON_FA.get(str(reason).strip(), str(reason).strip())


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s",
)


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


def main():
    api = ToobitAPI()
    symbols = api.get_futures_symbols(limit=MAX_SYMBOLS)
    logging.info("Scanning %d most liquid active USDT perpetual contracts", len(symbols))

    checked = 0
    alerts = 0
    for symbol in symbols:
        try:
            candles = api.get_multi_timeframe(symbol)
            result = analyze_market(candles)
            signal = result.get("signal", "WAIT")
            price = result.get("price")
            checked += 1

            logging.info(
                "%s | price=%s | signal=%s | score=%s | reason=%s",
                symbol, price, signal, result.get("score"),
                result.get("reason", "; ".join(result.get("reasons", []))),
            )

            if signal not in ("LONG", "SHORT"):
                continue

            atr = float(result.get("atr") or 0)
            if not price or atr <= 0:
                logging.warning("%s: missing price or ATR; alert skipped", symbol)
                continue

            direction = 1 if signal == "LONG" else -1
            stop = price - direction * 1.5 * atr
            target = price + direction * 3.0 * atr
            signal_fa = "خرید (لانگ)" if signal == "LONG" else "فروش (شورت)"
            reasons = result.get("reasons") or [result.get("reason", "")]
            reasons_fa = "؛ ".join(translate_reason(item) for item in reasons if item)
            score = result.get("score", "نامشخص")

            message = (
                f"جفت‌ارز: {symbol}\n"
                f"نوع سیگنال: {signal_fa}\n"
                f"قیمت ورود پیشنهادی: {price:.8g}\n"
                f"حد ضرر: {stop:.8g}\n"
                f"حد سود: {target:.8g}\n"
                f"امتیاز سیگنال: {score}\n"
                f"دلایل: {reasons_fa or 'در دسترس نیست'}\n"
                "فقط سیگنال است؛ هیچ سفارشی ثبت نشده است."
            )
            notify(f"سیگنال {signal_fa} | {symbol}", message, "high")
            alerts += 1

        except Exception:
            logging.exception("Analysis failed for %s; continuing with next contract", symbol)

    logging.info("Scan complete: %d contracts checked, %d alerts sent", checked, alerts)


if __name__ == "__main__":
    main()
