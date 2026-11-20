"""AURUM X by JAVAD — multi-indicator futures signal engine.

Read-only technical analysis. No order placement. Candles must be oldest -> newest
and contain open/high/low/close/volume. Optional taker_buy_volume is used only
when the exchange explicitly supplies it.
"""
from __future__ import annotations
from typing import Any, Dict, List, Optional, Sequence
from math import isfinite, sqrt
from expectancy import expectancy_allows

SUPPORTED_TFS = ("1m", "3m", "5m", "15m", "30m", "1h", "4h")
REQUIRED_TFS = ("5m", "15m", "30m")  # backward-compatible default
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
        st=_stoch_rsi(close); ad=_adx(c); ichi=_ichimoku(c); a=_atr(c)
        vw=_vwap(c); obv=_obv_trend(c); structure=_market_structure(c)
        volatility=_volatility_status(c,timeframe,a)
        regime=_adx_regime(ad)
        groups={}; details={}

        def vote(group,name,value,weight=1.0):
            if value not in (-1,0,1): return
            groups.setdefault(group,[]).append((value,weight,name))
            details[name]=value

        vote("trend","EMA",1 if e20>e50>e200 else -1 if e20<e50<e200 else 0,1.2)
        vote("trend","Ichimoku",1 if ichi and price>max(ichi["span_a"],ichi["span_b"]) and ichi["tenkan"]>ichi["kijun"] else -1 if ichi and price<min(ichi["span_a"],ichi["span_b"]) and ichi["tenkan"]<ichi["kijun"] else 0,1.2)
        vote("momentum","RSI",1 if r is not None and r>52 and r<70 else -1 if r is not None and r>30 and r<48 else 0)
        vote("momentum","MACD",1 if m["histogram"] is not None and m["histogram"]>0 else -1 if m["histogram"] is not None and m["histogram"]<0 else 0,1.2)
        vote("momentum","StochRSI",1 if st is not None and st>55 else -1 if st is not None and st<45 else 0)
        # ADX measures strength; DI+/DI- supply direction. ADX magnitude is not a direction gate.
        vote("strength","ADX_DMI",1 if ad and ad.get("plus_di",0)>ad.get("minus_di",0) else -1 if ad and ad.get("minus_di",0)>ad.get("plus_di",0) else 0,1.2)
        vote("volume","VWAP",1 if vw and price>vw else -1 if vw and price<vw else 0)
        vote("volume","OBV_Trend",obv)
        vote("volatility","Bollinger",1 if bb and price>bb["middle"] else -1 if bb and price<bb["middle"] else 0)
        ob_tolerance = (a or price*0.001) * 0.25
        smc_bull = structure.get("structure_event") in ("BOS_up","CHoCH_up") or structure.get("liquidity_sweep")=="sell_side" or any(x.get("type")=="bullish" and x["low"]-ob_tolerance <= price <= x["high"]+ob_tolerance for x in structure.get("order_blocks",[]))
        smc_bear = structure.get("structure_event") in ("BOS_down","CHoCH_down") or structure.get("liquidity_sweep")=="buy_side" or any(x.get("type")=="bearish" and x["low"]-ob_tolerance <= price <= x["high"]+ob_tolerance for x in structure.get("order_blocks",[]))
         # SMC remains an independent entry-structure safeguard, not an extra indicator vote.

        # Each indicator family contributes once; correlated indicators do not
        # overwhelm the decision simply because a family contains more indicators.
        family_scores={}
        for family,items in groups.items():
            denom=sum(w for _,w,_ in items)
            family_scores[family]=sum(v*w for v,w,_ in items)/denom if denom else 0
        family_weights={"trend":1.5,"momentum":1.3,"strength":1.1,"volume":1.0,"volatility":0.8}
        total_w=sum(family_weights[k] for k in family_scores)
        score=100*sum(family_scores[k]*family_weights[k] for k in family_scores)/total_w
        bias=_family_bias(family_scores)
        return {"valid":True,"close":price,"score":round(score,2),"bias":bias,
                "family_scores":{k:round(v,3) for k,v in family_scores.items()},
                "indicator_votes":details,"volatility_filter":volatility,"high_volatility":volatility["high_volatility"],
                "market_regime":regime,
                "ema21":e20,
                "indicators":{
                    "ema":{"20":e20,"50":e50,"200":e200},
                    "rsi":r,"macd":m,"bollinger":bb,"stoch_rsi":st,"adx_dmi":ad,
                    "ichimoku":ichi,"atr":a,"vwap":vw,"obv":obv,"smc":structure}}
    except (KeyError,TypeError,ValueError,ZeroDivisionError) as exc:
        return {"valid":False,"reason":f"Invalid candle data: {exc}"}


def _family_bias(family_scores: Dict[str, float]) -> str:
    """Choose direction from family votes; score magnitude never gates the bias."""
    bullish = sum(1 for value in family_scores.values() if float(value) > 0.0)
    bearish = sum(1 for value in family_scores.values() if float(value) < 0.0)
    return "bullish" if bullish > bearish else "bearish" if bearish > bullish else "neutral"


def _no_major_indicator_conflict(report: Dict[str, Any], side: str) -> bool:
    """Allow one minor counter-vote, but never a core trend/momentum/strength conflict."""
    votes = report.get("indicator_votes")
    if not isinstance(votes, dict) or not votes:
        return True  # Compatibility for synthetic/legacy reports; live analysis always supplies votes.
    direction = 1 if side == "LONG" else -1
    core = {"EMA", "Ichimoku", "RSI", "MACD", "StochRSI", "ADX_DMI", "VWAP", "OBV_Trend", "Bollinger"}
    opposing_core = sum(1 for name in core if int(votes.get(name, 0)) * direction < 0)
    opposing_minor = sum(1 for name, value in votes.items()
                         if name not in core and int(value) * direction < 0)
    return opposing_core == 0 and opposing_minor <= 1


def _entry_not_chasing(price: float, ema21: Optional[float], atr: Optional[float],
                       max_distance_atr: float = 0.80) -> bool:
    """Reject entries stretched too far from EMA21, using only the closed decision candle."""
    if ema21 is None or atr is None or atr <= 0:
        return False
    return abs(float(price) - float(ema21)) / float(atr) <= max_distance_atr


def analyze_market(timeframes: Dict[str,List[Dict[str,Any]]], expectancy_stats: Optional[Dict[str,List[float]]] = None, research_warmup: bool = False, selected_timeframes: Optional[Sequence[str]] = None) -> Dict[str,Any]:
    """Analyze only selected timeframes; every selected timeframe must agree."""
    selected_tfs = tuple(dict.fromkeys(REQUIRED_TFS if selected_timeframes is None else selected_timeframes))
    if not selected_tfs or any(tf not in SUPPORTED_TFS for tf in selected_tfs):
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"Invalid or empty selected timeframe list","trade_enabled":False}
    missing = [tf for tf in selected_tfs if not timeframes.get(tf)]
    if missing:
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"Missing selected timeframe data: "+", ".join(missing),"trade_enabled":False}
    # Timestamped live data is frozen to one common close boundary to prevent look-ahead.
    timestamped = [all(any(k in row for k in ("timestamp", "open_time", "time")) for row in timeframes.get(tf, [])) for tf in selected_tfs]
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
            if len(stamps) < 200 or any(t < 0 or t % d for t in stamps) or any(b-a != d for a,b in zip(stamps,stamps[1:])):
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
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"Insufficient/invalid candles: "+", ".join(invalid),
                "timeframes":reports,"trade_enabled":False}
    weights={tf:1.0/len(selected_tfs) for tf in selected_tfs}
    combined=sum(reports[tf]["score"]*weights[tf] for tf in selected_tfs)
    long_tfs=[tf for tf in selected_tfs if reports[tf]["bias"]=="bullish"]
    short_tfs=[tf for tf in selected_tfs if reports[tf]["bias"]=="bearish"]
    # Strict unanimity: one opposing or neutral timeframe vetoes the signal.
    side = "LONG" if len(long_tfs)==len(selected_tfs) else "SHORT" if len(short_tfs)==len(selected_tfs) else None
    aligned_tfs = list(selected_tfs) if side else []
    major_conflicts_clear = bool(side) and all(_no_major_indicator_conflict(reports[tf], side) for tf in selected_tfs)
    signal = side if major_conflicts_clear else "NO_SIGNAL"
    confidence=min(99,round(abs(combined)*0.75+max(longs,shorts)*8,1))
    result={"signal":signal,"status":signal,"confidence_score":confidence,
            "combined_score":round(combined,2),"timeframes":reports,
            "timeframe_weights":weights,"trade_enabled":False,
            "note":"Read-only analysis; confidence is a model score, not a probability or guarantee."}
    rejection_reasons = []
    if signal == "NO_SIGNAL":
        if side is None:
            rejection_reasons.append("selected_timeframe_unanimity_required")
        if not major_conflicts_clear and side is not None:
            rejection_reasons.append("major_indicator_conflict")
        if not rejection_reasons:
            rejection_reasons.append("directional_candidate_not_formed")
    result["rejection_reasons"] = rejection_reasons
    blocked=[tf for tf in selected_tfs if reports[tf].get("high_volatility")]
    if blocked:
        result.update({"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                       "reason":"Abnormal volatility filter blocked: "+", ".join(blocked),
                       "rejection_reasons":["high_volatility:"+tf for tf in blocked],
                       "high_volatility_block":blocked})
        return result
    # Range suppression considers only selected higher timeframes; unselected TFs never gate.
    entry_tf = min(selected_tfs, key=lambda tf: SUPPORTED_TFS.index(tf))
    higher_selected = [tf for tf in selected_tfs if SUPPORTED_TFS.index(tf) > SUPPORTED_TFS.index(entry_tf)]
    higher_timeframe_range = [tf for tf in higher_selected
        if (reports[tf].get("market_regime") or {}).get("state") == "ranging"]
    if len(higher_selected) >= 2 and len(higher_timeframe_range) == len(higher_selected):
        result.update({"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                       "reason":"All selected higher timeframes indicate a low-trend-strength market",
                       "rejection_reasons":["adx_selected_higher_timeframes_all_ranging"],
                       "blocked_market_regime":higher_timeframe_range})
        return result
    if signal in ("LONG","SHORT"):
        side_history = expectancy_stats.get(signal, []) if isinstance(expectancy_stats, dict) else []
        allowed, expectancy = (expectancy_allows(expectancy_stats, signal)
                                if expectancy_stats is not None and not (research_warmup and len(side_history) < 30)
                                else (True, {"allowed": True, "count": len(side_history), "reason": "research_warmup"}))
        result["expectancy"] = expectancy
        if not allowed:
            return {**result,"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                    "entry_stage":"WAIT","reason":"Closed-trade net expectancy is not statistically established for this symbol and direction.",
                    "rejection_reasons":["net_expectancy_gate"],"trade_enabled":False}
        a=reports[entry_tf]["indicators"]["atr"]; p=reports[entry_tf]["close"]
        if not a or a<=0: return {**result,"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"ATR unavailable"}
        smc = reports[entry_tf]["indicators"].get("smc", {})
        # Real candle reports must complete the breakout -> retest -> directional close sequence.
        # Real directional signals always require explicit, positive retest evidence.
        retest_key = "retest_long" if signal=="LONG" else "retest_short"
        confirmation = (smc.get("structure_event") in (("BOS_up","CHoCH_up") if signal=="LONG" else ("BOS_down","CHoCH_down"))
                        or smc.get("liquidity_sweep") == ("sell_side" if signal=="LONG" else "buy_side"))
        result["entry_stage"] = "ENTRY" if smc and smc.get(retest_key) else "CONFIRMATION" if confirmation else "PREPARATION"
        result["entry_context"] = {
            "preparation": all(reports[tf]["bias"] == side for tf in selected_tfs),
            "confirmation": confirmation,
            "retest_confirmed": bool(smc.get(retest_key)),
            "false_breakout": bool(smc.get("false_breakout"))
        }
        if smc.get("false_breakout"):
            return {**result,"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                    "entry_stage":"WAIT",
                    "reason":"Failed breakout detected; invalidate the setup and wait for a fresh structure.",
                    "rejection_reasons":["smc_false_breakout"],"trade_enabled":False}
        if not smc.get(retest_key):
            return {**result,"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                    "reason":"Setup is not entry-ready: wait for confirmed retest; do not chase the breakout.",
                    "rejection_reasons":["smc_retest_not_confirmed"],"trade_enabled":False}
        distance = smc.get("entry_distance_atr")
        if distance is None or distance > 0.50:
            return {**result,"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                    "entry_stage":"WAIT",
                    "reason":"Retest is missing or overextended (>0.50 ATR); skip late entry.",
                    "rejection_reasons":["smc_retest_distance_over_0_50_atr"],"trade_enabled":False}
        ema21 = reports[entry_tf].get("ema21")
        if not _entry_not_chasing(p, ema21, a):
            return {**result,"signal":"NO_SIGNAL","status":"NO_SIGNAL",
                    "entry_stage":"WAIT",
                    "reason":"Entry is overextended from EMA21 (>0.80 ATR); skip chasing the move.",
                    "rejection_reasons":["ema21_entry_distance_over_0_80_atr"],"trade_enabled":False}
        result["entry"] = p
        result["entry_stage"] = "ENTRY"
    else:
        smc = reports[entry_tf]["indicators"].get("smc", {})
        direction = side if major_conflicts_clear else None
        confirmation = bool(direction and (
            smc.get("structure_event") in (("BOS_up","CHoCH_up") if direction=="LONG" else ("BOS_down","CHoCH_down"))
            or smc.get("liquidity_sweep") == ("sell_side" if direction=="LONG" else "buy_side")
        ))
        retest_key = "retest_long" if direction=="LONG" else "retest_short"
        retest_ready = bool(direction and smc.get(retest_key))
        result["entry_stage"] = "ENTRY" if retest_ready else "CONFIRMATION" if confirmation else "PREPARATION" if direction else "WAIT"
        result["reason"]="Retest confirmed; waiting for entry safeguards" if retest_ready else "All selected timeframes must agree and entry retest must be confirmed"
    return result


def generate_signal(timeframes, mode="auto", selected_timeframes=None):
    """Compatibility alias; defaults to the legacy 5m/15m/30m selection."""
    if mode not in ("auto","trend","scalp"):
        raise ValueError("mode must be auto, scalp or trend")
    return analyze_market(timeframes, selected_timeframes=selected_timeframes)
