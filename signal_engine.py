"""AURUM X by JAVAD — multi-indicator futures signal engine.

Read-only technical analysis. No order placement. Candles must be oldest -> newest
and contain open/high/low/close/volume. Optional taker_buy_volume is used only
when the exchange explicitly supplies it.
"""
from __future__ import annotations
from typing import Any, Dict, List, Optional, Sequence
from math import isfinite, sqrt

SUPPORTED_TFS = ("1m", "3m", "5m", "15m", "30m", "1h", "4h")
# Kept only for legacy fixed-window research/backtest modules; live signal selection
# uses the user's actual selected timeframe keys and never requires this tuple.
REQUIRED_TFS = ("5m", "15m", "30m")
MIN_INDICATOR_CANDLES = 200


def _series(c, key):
    return [float(x[key]) for x in c]


def _sma(v, n):
    return sum(v[-n:]) / n if len(v) >= n else None


def _ema(v, n):
    if len(v) < n: return None
    a = 2.0 / (n + 1.0)
    out = sum(v[:n]) / n
    for x in v[n:]: out = a*x + (1-a)*out
    return out


def _rsi(v, n=14):
    if len(v) < n+1: return None
    d = [v[i]-v[i-1] for i in range(1,len(v))]
    g = [max(x,0) for x in d]; loss = [max(-x,0) for x in d]
    ag=sum(g[:n])/n; al=sum(loss[:n])/n
    for i in range(n,len(d)):
        ag=(ag*(n-1)+g[i])/n; al=(al*(n-1)+loss[i])/n
    return 100.0 if al == 0 else 100-(100/(1+ag/al))


def _ema_series(v, n):
    """EMA values with the same SMA seed as _ema, computed in linear time."""
    out = [None] * len(v)
    if len(v) < n:
        return out
    alpha = 2.0 / (n + 1.0)
    value = sum(v[:n]) / n
    out[n-1] = value
    for i in range(n, len(v)):
        value = alpha * v[i] + (1.0-alpha) * value
        out[i] = value
    return out


def _macd(v):
    if len(v)<35: return {"line":None,"signal":None,"histogram":None}
    fast = _ema_series(v, 12)
    slow = _ema_series(v, 26)
    vals = [fast[i] - slow[i] for i in range(25, len(v))]
    line = vals[-1]
    sig = _ema(vals, 9)
    return {"line":line,"signal":sig,"histogram":line-sig if sig is not None else None}


def _atr(c,n=14):
    if len(c)<n+1: return None
    tr=[]
    for i in range(1,len(c)):
        h=float(c[i]["high"]); l=float(c[i]["low"]); pc=float(c[i-1]["close"])
        tr.append(max(h-l,abs(h-pc),abs(l-pc)))
    x=sum(tr[:n])/n
    for z in tr[n:]: x=(x*(n-1)+z)/n
    return x


def _bollinger(v,n=20):
    if len(v)<n: return {}
    w=v[-n:]; mid=sum(w)/n; sd=sqrt(sum((x-mid)**2 for x in w)/n)
    return {"middle":mid,"upper":mid+2*sd,"lower":mid-2*sd,
            "width":(4*sd/mid) if mid else None}


def _stoch_rsi(v,n=14):
    if len(v)<2*n+1: return None
    deltas = [v[i]-v[i-1] for i in range(1,len(v))]
    avg_gain = sum(max(x,0.0) for x in deltas[:n]) / n
    avg_loss = sum(max(-x,0.0) for x in deltas[:n]) / n
    rs = [100.0 if avg_loss == 0 else 100.0-100.0/(1.0+avg_gain/avg_loss)]
    for delta in deltas[n:]:
        avg_gain = (avg_gain*(n-1)+max(delta,0.0))/n
        avg_loss = (avg_loss*(n-1)+max(-delta,0.0))/n
        rs.append(100.0 if avg_loss == 0 else 100.0-100.0/(1.0+avg_gain/avg_loss))
    if len(rs)<n: return None
    w=rs[-n:]; lo=min(w); hi=max(w)
    return 0.0 if hi==lo else 100*(rs[-1]-lo)/(hi-lo)


def _adx(c,n=14):
    if len(c)<2*n+1: return {}
    plus=[]; minus=[]; trs=[]
    for i in range(1,len(c)):
        up=float(c[i]["high"])-float(c[i-1]["high"])
        dn=float(c[i-1]["low"])-float(c[i]["low"])
        plus.append(up if up>dn and up>0 else 0.0)
        minus.append(dn if dn>up and dn>0 else 0.0)
        h=float(c[i]["high"]); l=float(c[i]["low"]); pc=float(c[i-1]["close"])
        trs.append(max(h-l,abs(h-pc),abs(l-pc)))
    p=sum(plus[:n]); m=sum(minus[:n]); tr=sum(trs[:n]); dx=[]
    for i in range(n,len(trs)):
        p=p-p/n+plus[i]; m=m-m/n+minus[i]; tr=tr-tr/n+trs[i]
        if tr:
            pdi=100*p/tr; mdi=100*m/tr
            dx.append(100*abs(pdi-mdi)/(pdi+mdi) if pdi+mdi else 0)
    if len(dx)<n: return {}
    adx=sum(dx[:n])/n
    for x in dx[n:]: adx=(adx*(n-1)+x)/n
    return {"adx":adx,"plus_di":100*p/tr if tr else None,"minus_di":100*m/tr if tr else None}


def _ichimoku(c):
    if len(c)<52: return {}
    def mid(n):
        h=[float(x["high"]) for x in c[-n:]]
        l=[float(x["low"]) for x in c[-n:]]
        return (max(h)+min(l))/2
    ten=mid(9); kij=mid(26); span_a=(ten+kij)/2; span_b=mid(52)
    return {"tenkan":ten,"kijun":kij,"span_a":span_a,"span_b":span_b}


def _vwap(c,n=20):
    w=c[-n:]
    vol=sum(float(x.get("volume",0)) for x in w)
    if vol<=0: return None
    return sum(((float(x["high"])+float(x["low"])+float(x["close"]))/3)*float(x.get("volume",0)) for x in w)/vol


def _obv_trend(c, lookback=8):
    """Use recent OBV direction, not the arbitrary sign of its cumulative origin."""
    if len(c) < lookback + 1:
        return 0
    delta = 0.0
    for i in range(len(c) - lookback, len(c)):
        current = float(c[i]["close"])
        previous = float(c[i-1]["close"])
        volume = max(0.0, float(c[i].get("volume", 0)))
        delta += volume if current > previous else -volume if current < previous else 0.0
    return 1 if delta > 0 else -1 if delta < 0 else 0


def _volume_impulse(c, current_atr):
    """Directional high-participation candle; not a substitute for exchange order flow."""
    if len(c) < 26 or current_atr is None or current_atr <= 0:
        return 0
    recent = sum(max(0.0, float(x.get("volume", 0))) for x in c[-5:]) / 5
    baseline = sum(max(0.0, float(x.get("volume", 0))) for x in c[-25:-5]) / 20
    if baseline <= 0 or recent / baseline < 1.3:
        return 0
    last = c[-1]
    o, h, low, close = (float(last[k]) for k in ("open", "high", "low", "close"))
    span = h - low
    if span <= 0 or abs(close - o) < current_atr * 0.35:
        return 0
    location = (close - low) / span
    return 1 if close > o and location >= 0.70 else -1 if close < o and location <= 0.30 else 0


def _mfi(c,n=14):
    if len(c)<n+1: return None
    pos=neg=0.0
    for i in range(len(c)-n,len(c)):
        tp=lambda x:(float(x["high"])+float(x["low"])+float(x["close"]))/3
        flow=tp(c[i])*float(c[i].get("volume",0))
        if tp(c[i])>tp(c[i-1]): pos+=flow
        elif tp(c[i])<tp(c[i-1]): neg+=flow
    if neg==0: return 100.0
    return 100-(100/(1+pos/neg))


def _confirmed_pivots(values, left=2, right=2):
    """Return confirmed local pivot indices; the right window avoids look-ahead."""
    highs, lows = [], []
    for i in range(left, len(values) - right):
        window = values[i-left:i+right+1]
        if values[i] == max(window) and window.count(values[i]) == 1:
            highs.append(i)
        if values[i] == min(window) and window.count(values[i]) == 1:
            lows.append(i)
    return highs, lows


def _divergence(c, lookback=48):
    """Regular RSI divergence between the last two confirmed price pivots."""
    if len(c) < lookback + 20:
        return "none"
    closes = _series(c, "close")
    highs, lows = _confirmed_pivots(closes[-lookback-2:], 2, 2)
    offset = len(closes) - lookback - 2
    lows = [i + offset for i in lows]
    highs = [i + offset for i in highs]
    if len(lows) >= 2:
        p1, p2 = lows[-2:]
        r1, r2 = _rsi(closes[:p1+1]), _rsi(closes[:p2+1])
        if r1 is not None and r2 is not None and closes[p2] < closes[p1] and r2 > r1 + 3:
            return "bullish"
    if len(highs) >= 2:
        p1, p2 = highs[-2:]
        r1, r2 = _rsi(closes[:p1+1]), _rsi(closes[:p2+1])
        if r1 is not None and r2 is not None and closes[p2] > closes[p1] and r2 < r1 - 3:
            return "bearish"
    return "none"


def _market_structure(c, lookback=60):
    """Causal swing/liquidity model: confirmed pivots, close-confirmed breaks and fresh retests."""
    if len(c) < max(lookback, 30):
        return {}
    w = c[-lookback:]
    highs = [float(x["high"]) for x in w]
    lows = [float(x["low"]) for x in w]
    ph, pl = _confirmed_pivots(highs, 2, 2)
    # Exclude pivots confirmed only by the final two bars: they were not
    # available when the current decision candle began.
    swing_highs = [(i, highs[i]) for i in ph if i < len(w)-4]
    swing_lows = [(i, lows[i]) for i in pl if i < len(w)-4]
    atr = _atr(w)
    last = w[-1]
    op, close = float(last["open"]), float(last["close"])
    high, low = float(last["high"]), float(last["low"])
    span = max(high-low, 1e-12)
    body = abs(close-op)
    close_pos = (close-low)/span
    atr_ref = atr or max(close*0.001, 1e-12)
    displacement = body >= atr_ref*0.35
    tolerance = max(atr_ref*0.20, close*0.0005)
    break_buffer = atr_ref*0.10

    # Conservative fallback when the sample has no confirmed two-sided fractals.
    if not swing_highs or not swing_lows:
        prior = c[-21:-1]
        if len(prior) < 10:
            return {}
        reference = prior[:-6] if len(prior) >= 16 else prior
        sh = max(float(x["high"]) for x in reference)
        sl = min(float(x["low"]) for x in reference)
        recent = prior[-6:]
        high_pivots, low_pivots = [(0, sh)], [(0, sl)]
    else:
        sh, sl = swing_highs[-1][1], swing_lows[-1][1]
        recent = w[-7:-1]
        high_pivots, low_pivots = swing_highs, swing_lows

    up_trend = (len(swing_highs) >= 2 and len(swing_lows) >= 2
                and swing_highs[-1][1] > swing_highs[-2][1]
                and swing_lows[-1][1] > swing_lows[-2][1])
    down_trend = (len(swing_highs) >= 2 and len(swing_lows) >= 2
                  and swing_highs[-1][1] < swing_highs[-2][1]
                  and swing_lows[-1][1] < swing_lows[-2][1])

    # A break requires a candle close beyond the confirmed swing plus displacement.
    event = "none"
    if close > sh + break_buffer and displacement and close_pos >= 0.70:
        event = "CHoCH_up" if down_trend else "BOS_up"
    elif close < sl - break_buffer and displacement and close_pos <= 0.30:
        event = "CHoCH_down" if up_trend else "BOS_down"

    # Liquidity sweep: wick takes a known pool, but the candle closes back inside it.
    sweep = "none"
    if low < sl - tolerance*0.10 and close > sl and close_pos >= 0.55:
        sweep = "sell_side"
    elif high > sh + tolerance*0.10 and close < sh and close_pos <= 0.45:
        sweep = "buy_side"

    # Equal-high/low pools are grouped by ATR-scaled proximity, not exact equality.
    recent_highs = [v for _, v in high_pivots[-6:]]
    recent_lows = [v for _, v in low_pivots[-6:]]
    equal_highs = [v for v in recent_highs if abs(v-sh) <= tolerance]
    equal_lows = [v for v in recent_lows if abs(v-sl) <= tolerance]

    # Only a breakout from the immediately preceding six bars can qualify for retest.
    prior_up = any(float(x["close"]) > sh + break_buffer for x in recent)
    prior_down = any(float(x["close"]) < sl - break_buffer for x in recent)
    bull_candle = close > op and close_pos >= 0.60
    bear_candle = close < op and close_pos <= 0.40
    # A sweep alone is not an entry trigger. Require a prior close-confirmed
    # structural break followed by a directional retest; this rejects wick-only traps.
    retest_long = bool(prior_up and low <= sh+tolerance and close > sh and bull_candle)
    retest_short = bool(prior_down and high >= sl-tolerance and close < sl and bear_candle)

    # A close back through the broken level invalidates the breakout; a wick-only sweep
    # is separately reported and may become a reversal setup after a directional close.
    failed_up = prior_up and close < sh - tolerance
    failed_down = prior_down and close > sl + tolerance
    false_breakout = bool(failed_up or failed_down)
    order_blocks = []
    for j in range(len(w)-2, max(-1, len(w)-21), -1):
        x, nxt = w[j], w[j+1]
        xo, xc = float(x["open"]), float(x["close"])
        nh, nl, nc = float(nxt["high"]), float(nxt["low"]), float(nxt["close"])
        nr = max(nh-nl, 1e-12)
        impulse_body = abs(nc-float(nxt["open"]))
        # Displacement must be material relative to ATR, not merely a one-tick close.
        bullish_valid = (xc < xo and nc > float(x["high"]) and nc >= nh-nr*0.25
                         and impulse_body >= atr_ref*0.50
                         and not any(float(z["close"]) < float(x["low"]) for z in w[j+2:]))
        bearish_valid = (xc > xo and nc < float(x["low"]) and nc <= nl+nr*0.25
                         and impulse_body >= atr_ref*0.50
                         and not any(float(z["close"]) > float(x["high"]) for z in w[j+2:]))
        if bullish_valid:
            order_blocks.append({"type":"bullish","low":float(x["low"]),"high":float(x["high"])})
            break
        if bearish_valid:
            order_blocks.append({"type":"bearish","low":float(x["low"]),"high":float(x["high"])})
            break

    if retest_long:
        entry_level = sh if prior_up else sl
        entry_distance_atr = max(0.0, (close-entry_level)/atr_ref)
    elif retest_short:
        entry_level = sl if prior_down else sh
        entry_distance_atr = max(0.0, (entry_level-close)/atr_ref)
    else:
        entry_level, entry_distance_atr = None, None

    quality = ("false" if false_breakout else "retest_confirmed" if retest_long or retest_short
               else "sweep" if sweep != "none" else "valid_breakout" if event != "none"
               else "none")
    return {
        "previous_swing_high":sh, "previous_swing_low":sl,
        "structure_event":event,
        "market_structure":"bullish" if up_trend else "bearish" if down_trend else "range",
        "liquidity_sweep":sweep,
        "equal_high_liquidity":len(equal_highs)>=2,
        "equal_low_liquidity":len(equal_lows)>=2,
        "equal_high_level":sh if len(equal_highs)>=2 else None,
        "equal_low_level":sl if len(equal_lows)>=2 else None,
        "order_blocks":order_blocks,
        "swing_high_count":len(swing_highs), "swing_low_count":len(swing_lows),
        "false_breakout":false_breakout,
        "retest_long":retest_long, "retest_short":retest_short,
        "retest_level":entry_level, "entry_distance_atr":entry_distance_atr,
        "breakout_quality":quality
    }



def _adx_regime(ad):
    value = ad.get("adx") if ad else None
    if value is None:
        return {"state":"unknown","adx":None,"direction":"neutral"}
    direction = "bullish" if ad.get("plus_di",0) > ad.get("minus_di",0) else "bearish" if ad.get("minus_di",0) > ad.get("plus_di",0) else "neutral"
    state = "trending" if value >= 25 else "ranging" if value < 18 else "transition"
    return {"state":state,"adx":round(value,2),"direction":direction}

def _taker_strength(c):
    rows=[x for x in c if x.get("taker_buy_volume") is not None]
    if not rows: return {"available":False,"reason":"exchange does not provide taker_buy_volume"}
    buy=sum(float(x["taker_buy_volume"]) for x in rows)
    total=sum(float(x.get("volume",0)) for x in rows)
    if total<=0: return {"available":False,"reason":"zero volume"}
    sell=max(0,total-buy)
    return {"available":True,"buy_percent":round(100*buy/total,2),
            "sell_percent":round(100*sell/total,2)}



def _volatility_status(c, timeframe="5m", current_atr=None):
    """Flag exceptional ATR expansion versus the recent ATR%-baseline."""
    if current_atr is None:
        current_atr = _atr(c)
    if not current_atr or not c or float(c[-1]["close"]) <= 0:
        return {"atr_pct": None, "baseline_atr_pct": None,
                "ratio": None, "high_volatility": False}
    atr_pct = 100.0 * current_atr / float(c[-1]["close"])
    start = max(15, len(c) - 30)
    baseline = []
    for end in range(start, len(c) - 1):
        value = _atr(c[:end + 1])
        close = float(c[end]["close"])
        if value is not None and close > 0:
            baseline.append(100.0 * value / close)
    if not baseline:
        return {"atr_pct": round(atr_pct, 4), "baseline_atr_pct": None,
                "ratio": None, "high_volatility": False}
    ordered = sorted(baseline)
    mid = len(ordered) // 2
    median = ordered[mid] if len(ordered) % 2 else (ordered[mid-1] + ordered[mid]) / 2
    ratio = atr_pct / median if median > 0 else None
    # The relative expansion test avoids blocking consistently volatile markets.
    # Absolute floors prevent tiny baselines from creating false alarms.
    floor = {"5m": 0.8, "15m": 1.2, "30m": 1.8}.get(timeframe, 0.8)
    blocked = bool(ratio is not None and atr_pct >= floor and ratio >= 2.5)
    return {"atr_pct": round(atr_pct, 4),
            "baseline_atr_pct": round(median, 4),
            "ratio": round(ratio, 3) if ratio is not None else None,
            "high_volatility": blocked}


def analyze_timeframe(candles: List[Dict[str, Any]], timeframe="5m") -> Dict[str, Any]:
    if len(candles)<MIN_INDICATOR_CANDLES:
        return {"valid":False,"reason":"At least 200 candles required for EMA200 and stable indicator calculation"}
    try:
        # Every candle participates in recursive indicators; validating only
        # the last 60 bars can let an older NaN/invalid OHLC value poison the
        # entire EMA/ATR history while the current tail appears valid.
        for index, x in enumerate(candles):
            o, h, low, close_value, volume = (
                float(x[k]) for k in ("open", "high", "low", "close", "volume")
            )
            taker_buy = x.get("taker_buy_volume")
            if taker_buy is not None:
                taker_buy = float(taker_buy)
            if (not all(isfinite(v) for v in (o, h, low, close_value, volume))
                    or min(o, h, low, close_value) <= 0 or volume < 0
                    or h < max(o, close_value) or low > min(o, close_value)
                    or h < low
                    or (taker_buy is not None and
                        (not isfinite(taker_buy) or taker_buy < 0 or taker_buy > volume * 1.001))):
                raise ValueError(f"invalid OHLCV/order-flow values at candle index {index}")
        c=candles; close=_series(c,"close"); price=close[-1]
        e20=_ema(close,20); e50=_ema(close,50); e200=_ema(close,200)
        r=_rsi(close); m=_macd(close); bb=_bollinger(close)
        ad=_adx(c); a=_atr(c)
        vw=_vwap(c); obv=_obv_trend(c)
        volatility=_volatility_status(c,timeframe,a)
        regime=_adx_regime(ad)
        details={}
        def vote(name,value):
            if value not in (-1,0,1): return
            details[name]=value

        vote("EMA",1 if e20>e50 and price>e200 else -1 if e20<e50 and price<e200 else 0)
        # RSI midpoint momentum; extremes do not veto an otherwise valid trend.
        vote("RSI",1 if r is not None and r>=50 else -1 if r is not None and r<50 else 0)
        vote("MACD",1 if m["histogram"] is not None and m["histogram"]>0 else -1 if m["histogram"] is not None and m["histogram"]<0 else 0)
        # DI+/DI- directly identify directional pressure.
        vote("ADX_DMI",1 if ad and ad.get("plus_di",0)>ad.get("minus_di",0) else -1 if ad and ad.get("minus_di",0)>ad.get("plus_di",0) else 0)
        vote("VWAP",1 if vw is not None and price>vw else -1 if vw is not None and price<vw else 0)
        vote("OBV_Trend",obv)
        vote("Bollinger",1 if bb and price>bb["middle"] else -1 if bb and price<bb["middle"] else 0)
        bias=_indicator_bias(details)
        smc=_market_structure(c)
        return {"valid":True,"close":price,"bias":bias,"smc":smc,
                "indicator_votes":details,"volatility_filter":volatility,"high_volatility":volatility["high_volatility"],
                "market_regime":regime,"ema21":e20,
                "indicators":{
                    "ema":{"20":e20,"50":e50,"200":e200},
                    "rsi":r,"macd":m,"bollinger":bb,"adx_dmi":ad,
                    "atr":a,"vwap":vw,"obv":obv}}
    except (KeyError,TypeError,ValueError,ZeroDivisionError) as exc:
        return {"valid":False,"reason":f"Invalid candle data: {exc}"}


def _entry_not_chasing(price: float, ema21: Optional[float], atr: Optional[float],
                       max_distance_atr: float = 0.80) -> bool:
    """Reject entries stretched too far from EMA21, using only the closed decision candle."""
    if ema21 is None or atr is None or atr <= 0:
        return False
    return abs(float(price) - float(ema21)) / float(atr) <= max_distance_atr


def _levels(side, entry, atr, structure=None):
    """Compatibility helper for research reports; levels are descriptive, not order instructions."""
    if side not in ("LONG", "SHORT") or atr is None or float(atr) <= 0:
        raise ValueError("side must be LONG/SHORT and ATR must be positive")
    entry, atr = float(entry), float(atr)
    structure = structure or {}
    swing = structure.get("previous_swing_low" if side == "LONG" else "previous_swing_high")
    buffer = atr * 0.15
    if side == "LONG":
        stop = min(entry - atr * 1.5, float(swing) - buffer) if swing is not None else entry - atr * 1.5
        risk = entry - stop
        targets = [entry + risk * multiple for multiple in (1.5, 2.5, 3.5)]
    else:
        stop = max(entry + atr * 1.5, float(swing) + buffer) if swing is not None else entry + atr * 1.5
        risk = stop - entry
        targets = [entry - risk * multiple for multiple in (1.5, 2.5, 3.5)]
    return {"stop_loss": stop, "take_profits": targets, "risk_reward": [1.5, 2.5, 3.5]}

def _indicator_bias(indicator_directions: Dict[str, int]) -> str:
    """Direct logical conditions only: EMA and DMI align; RSI OR MACD confirms."""
    long_momentum = (indicator_directions.get("RSI") == 1 or
                     indicator_directions.get("MACD") == 1)
    short_momentum = (indicator_directions.get("RSI") == -1 or
                      indicator_directions.get("MACD") == -1)
    if (indicator_directions.get("EMA") == 1 and
            indicator_directions.get("ADX_DMI") == 1 and long_momentum):
        return "bullish"
    if (indicator_directions.get("EMA") == -1 and
            indicator_directions.get("ADX_DMI") == -1 and short_momentum):
        return "bearish"
    return "neutral"


def _smc_confirms(structure: Dict[str, Any], side: str) -> bool:
    """Independent SMC entry filter; no weights or scores are involved."""
    if not structure or structure.get("false_breakout", False):
        return False
    blocks = structure.get("order_blocks") or []
    if side == "LONG":
        if blocks and blocks[0].get("type") == "bearish":
            return False
        return (structure.get("structure_event") in ("BOS_up", "CHoCH_up")
                or structure.get("retest_long", False)
                or structure.get("liquidity_sweep") == "sell_side")
    if side == "SHORT":
        if blocks and blocks[0].get("type") == "bullish":
            return False
        return (structure.get("structure_event") in ("BOS_down", "CHoCH_down")
                or structure.get("retest_short", False)
                or structure.get("liquidity_sweep") == "buy_side")
    return False


def analyze_market(timeframes: Dict[str,List[Dict[str,Any]]], selected_timeframes: Optional[Sequence[str]] = None) -> Dict[str,Any]:
    """Analyze only selected timeframes; one timeframe is independent, several require unanimity."""
    if selected_timeframes is None:
        selected_tfs = tuple(tf for tf in SUPPORTED_TFS if tf in timeframes)
    else:
        selected_tfs = tuple(dict.fromkeys(selected_timeframes))
    if not selected_tfs or any(tf not in SUPPORTED_TFS for tf in selected_tfs):
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"Invalid or empty selected timeframe list","trade_enabled":False}
    missing = [tf for tf in selected_tfs if tf not in timeframes]
    if missing:
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"Missing selected timeframe data: "+", ".join(missing),"trade_enabled":False}
    # Timestamp metadata is all-or-nothing. A partially timestamped series must
    # never fall back to legacy untimestamped mode, because that bypasses the
    # closed-candle and continuity checks below.
    timestamped = []
    for tf in selected_tfs:
        rows = timeframes.get(tf, [])
        row_has_timestamp = [
            any(key in row for key in ("timestamp", "open_time", "time"))
            for row in rows
        ]
        has_any_timestamp = any(row_has_timestamp)
        has_all_timestamps = bool(row_has_timestamp) and all(row_has_timestamp)
        if has_any_timestamp and not has_all_timestamps:
            return {
                "signal": "NO_SIGNAL", "status": "NO_SIGNAL",
                "reason": f"Incomplete timestamps in selected {tf} candle data",
                "trade_enabled": False,
            }
        timestamped.append(has_all_timestamps)
    if any(timestamped) and not all(timestamped):
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"Mixed timestamped/untimestamped selected timeframe data","trade_enabled":False}
    analysis_timeframes = {tf:list(timeframes.get(tf,[])) for tf in selected_tfs}
    if all(timestamped):
        durations = {"1m":60000,"3m":180000,"5m":300000,"15m":900000,"30m":1800000,"1h":3600000,"4h":14400000}
        opens = {}
        latest_closes = []
        for tf in selected_tfs:
            rows = timeframes.get(tf, [])
            try:
                stamps = [int(next(row[k] for k in ("timestamp","open_time","time") if k in row)) for row in rows]
            except (TypeError, ValueError, StopIteration):
                return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":f"Invalid {tf} timestamps","trade_enabled":False}
            d = durations[tf]
            if len(stamps) < MIN_INDICATOR_CANDLES or any(t < 0 or t % d for t in stamps) or any(b-a != d for a,b in zip(stamps,stamps[1:])):
                return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":f"Non-contiguous or insufficient {tf} candles","trade_enabled":False}
            opens[tf] = stamps
            latest_closes.append(stamps[-1] + d)
        decision_time = min(latest_closes)
        for tf in selected_tfs:
            d = durations[tf]
            expected_open = (decision_time // d) * d - d
            if expected_open not in opens[tf]:
                return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":f"{tf} has no fully closed candle at the common decision boundary","trade_enabled":False}
            analysis_timeframes[tf] = [row for row in analysis_timeframes[tf]
                if int(next(row[k] for k in ("timestamp","open_time","time") if k in row)) <= expected_open]
    reports={tf:analyze_timeframe(analysis_timeframes.get(tf,[]),tf) for tf in selected_tfs}
    invalid=[tf for tf,r in reports.items() if not r.get("valid")]
    if invalid:
        detail = "; ".join(
            f"{tf}: {reports[tf].get('reason', 'invalid candle data')}" for tf in invalid)
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                "reason":"Insufficient/invalid candles: "+detail,
                "timeframes":reports,"diagnostics":{tf: reports[tf].get("reason", "invalid candle data") for tf in invalid},
                "trade_enabled":False}
    all_bullish=all(reports[tf]["bias"]=="bullish" for tf in selected_tfs)
    all_bearish=all(reports[tf]["bias"]=="bearish" for tf in selected_tfs)
    side="LONG" if all_bullish else "SHORT" if all_bearish else None
    diagnostics = {
        tf: {"bias": reports[tf]["bias"],
             "indicator_directions": reports[tf].get("indicator_votes", {}),
             "explanation": ("EMA and DMI must align, with RSI or MACD confirming; supporting indicators are informational only.")}
        for tf in selected_tfs
    }
    if side is None:
        detail = "; ".join(
            f"{tf}: {reports[tf]['bias']} | " +
            ", ".join(f"{name}={value:+d}" for name, value in reports[tf].get("indicator_votes", {}).items())
            for tf in selected_tfs)
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                "reason":"All selected timeframes must independently agree; neutral or conflicting directions mean no signal. "+detail,
                "timeframes":reports,"diagnostics":diagnostics,"trade_enabled":False,
                "rejection_reasons":["selected_timeframe_unanimity_required"]}
    entry_tf=min(selected_tfs,key=lambda tf:SUPPORTED_TFS.index(tf))
    return {"signal":side,"status":side,"timeframes":reports,"entry":reports[entry_tf]["close"],
            "entry_timeframe":entry_tf,"entry_stage":"ENTRY","diagnostics":diagnostics,
            "reason":("Independent indicator analysis confirms "+side+" on "+entry_tf) if len(selected_tfs)==1
                     else ("All selected timeframes independently confirm "+side),
            "trade_enabled":False,"note":"Read-only technical analysis; no exchange orders are placed."}

def generate_signal(timeframes, mode="auto", selected_timeframes=None):
    """Compatibility alias; defaults to the timeframes present in the input mapping."""
    if mode not in ("auto","trend","scalp"):
        raise ValueError("mode must be auto, scalp or trend")
    return analyze_market(timeframes, selected_timeframes=selected_timeframes)
