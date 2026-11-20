"""AURUM X by JAVAD — multi-indicator futures signal engine.

Read-only technical analysis. No order placement. Candles must be oldest -> newest
and contain open/high/low/close/volume. Optional taker_buy_volume is used only
when the exchange explicitly supplies it.
"""
from __future__ import annotations
from typing import Any, Dict, List, Optional, Sequence
from math import sqrt

REQUIRED_TFS = ("5m", "15m", "30m")


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


def _macd(v):
    if len(v)<35: return {"line":None,"signal":None,"histogram":None}
    vals=[]
    for end in range(26,len(v)+1):
        f=_ema(v[:end],12); s=_ema(v[:end],26)
        if f is not None and s is not None: vals.append(f-s)
    line=_ema(v,12)-_ema(v,26); sig=_ema(vals,9)
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
    rs=[]
    for end in range(n+1,len(v)+1):
        rs.append(_rsi(v[:end],n))
    rs=[x for x in rs if x is not None]
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


def _obv(c):
    if len(c)<2: return None
    out=0.0
    for i in range(1,len(c)):
        a=float(c[i]["close"]); b=float(c[i-1]["close"]); vol=float(c[i].get("volume",0))
        out += vol if a>b else -vol if a<b else 0
    return out


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


def _divergence(c, lookback=30):
    if len(c)<lookback+15: return "none"
    closes=[float(x["close"]) for x in c]
    rs=[]
    for end in range(len(closes)-lookback-14,len(closes)+1):
        rs.append(_rsi(closes[:end]))
    if len(rs)<lookback+1: return "none"
    old_rsi=rs[0]; new_rsi=rs[-1]
    old_p=min(closes[-lookback-14:-14]); new_p=min(closes[-14:])
    old_ph=max(closes[-lookback-14:-14]); new_ph=max(closes[-14:])
    if new_p<old_p and new_rsi>old_rsi+3: return "bullish"
    if new_ph>old_ph and new_rsi<old_rsi-3: return "bearish"
    return "none"


def _market_structure(c, lookback=20):
    if len(c)<lookback+2: return {}
    prev=c[-lookback-1:-1]; last=c[-1]
    ph=max(float(x["high"]) for x in prev); pl=min(float(x["low"]) for x in prev)
    close=float(last["close"]); high=float(last["high"]); low=float(last["low"])
    event="none"; sweep="none"
    if close>ph: event="BOS_up"
    elif close<pl: event="BOS_down"
    if low<pl and close>pl: sweep="sell_side"
    elif high>ph and close<ph: sweep="buy_side"
    return {"previous_swing_high":ph,"previous_swing_low":pl,
            "structure_event":event,"liquidity_sweep":sweep}


def _taker_strength(c):
    rows=[x for x in c if x.get("taker_buy_volume") is not None]
    if not rows: return {"available":False,"reason":"exchange does not provide taker_buy_volume"}
    buy=sum(float(x["taker_buy_volume"]) for x in rows)
    total=sum(float(x.get("volume",0)) for x in rows)
    if total<=0: return {"available":False,"reason":"zero volume"}
    sell=max(0,total-buy)
    return {"available":True,"buy_percent":round(100*buy/total,2),
            "sell_percent":round(100*sell/total,2)}


def analyze_timeframe(candles: List[Dict[str, Any]]) -> Dict[str, Any]:
    if len(candles)<60:
        return {"valid":False,"reason":"At least 60 candles required for stable indicator calculation"}
    try:
        for x in candles[-60:]:
            for k in ("open","high","low","close","volume"): float(x[k])
        c=candles; close=_series(c,"close"); price=close[-1]
        e9=_ema(close,9); e21=_ema(close,21); e50=_ema(close,50)
        s20=_sma(close,20); s50=_sma(close,50)
        r=_rsi(close); m=_macd(close); bb=_bollinger(close)
        st=_stoch_rsi(close); ad=_adx(c); ichi=_ichimoku(c); a=_atr(c)
        vw=_vwap(c); obv=_obv(c); mfi=_mfi(c); structure=_market_structure(c)
        div=_divergence(c); strength=_taker_strength(c)
        groups={}; details={}

        def vote(group,name,value,weight=1.0):
            if value not in (-1,0,1): return
            groups.setdefault(group,[]).append((value,weight,name))
            details[name]=value

        vote("trend","EMA",1 if e9>e21>e50 else -1 if e9<e21<e50 else 0,1.2)
        vote("trend","SMA",1 if s20 and s50 and price>s20>s50 else -1 if s20 and s50 and price<s20<s50 else 0)
        vote("trend","Ichimoku",1 if ichi and price>max(ichi["span_a"],ichi["span_b"]) and ichi["tenkan"]>ichi["kijun"] else -1 if ichi and price<min(ichi["span_a"],ichi["span_b"]) and ichi["tenkan"]<ichi["kijun"] else 0,1.2)
        vote("momentum","RSI",1 if r is not None and 52<r<70 else -1 if r is not None and 30<r<48 else 0)
        vote("momentum","MACD",1 if m["histogram"] is not None and m["histogram"]>0 else -1 if m["histogram"] is not None and m["histogram"]<0 else 0,1.2)
        vote("momentum","StochRSI",1 if st is not None and st>55 else -1 if st is not None and st<45 else 0)
        vote("strength","ADX_DMI",1 if ad.get("adx",0)>=20 and ad.get("plus_di",0)>ad.get("minus_di",0) else -1 if ad.get("adx",0)>=20 and ad.get("minus_di",0)>ad.get("plus_di",0) else 0,1.2)
        vote("volume","VWAP",1 if vw and price>vw else -1 if vw and price<vw else 0)
        vote("volume","OBV",1 if obv is not None and obv>0 else -1 if obv is not None and obv<0 else 0)
        vote("volume","MFI",1 if mfi is not None and 50<mfi<80 else -1 if mfi is not None and 20<mfi<50 else 0)
        vote("volatility","Bollinger",1 if bb and price>bb["middle"] else -1 if bb and price<bb["middle"] else 0)
        vote("structure","SMC",1 if structure.get("structure_event")=="BOS_up" or structure.get("liquidity_sweep")=="sell_side" else -1 if structure.get("structure_event")=="BOS_down" or structure.get("liquidity_sweep")=="buy_side" else 0,1.3)
        vote("divergence","Divergence",1 if div=="bullish" else -1 if div=="bearish" else 0,1.1)
        vote("orderflow","TakerFlow",1 if strength.get("available") and strength["buy_percent"]>=55 else -1 if strength.get("available") and strength["sell_percent"]>=55 else 0)

        # Each indicator family contributes once; correlated indicators do not
        # overwhelm the decision simply because a family contains more indicators.
        family_scores={}
        for family,items in groups.items():
            denom=sum(w for _,w,_ in items)
            family_scores[family]=sum(v*w for v,w,_ in items)/denom if denom else 0
        family_weights={"trend":1.5,"momentum":1.3,"strength":1.1,"volume":1.0,
                        "volatility":0.6,"structure":1.4,"divergence":0.8,"orderflow":0.7}
        total_w=sum(family_weights[k] for k in family_scores)
        score=100*sum(family_scores[k]*family_weights[k] for k in family_scores)/total_w
        bullish=sum(1 for x in family_scores.values() if x>0.20)
        bearish=sum(1 for x in family_scores.values() if x<-0.20)
        bias="bullish" if score>=22 and bullish>=4 else "bearish" if score<=-22 and bearish>=4 else "neutral"
        return {"valid":True,"close":price,"score":round(score,2),"bias":bias,
                "family_scores":{k:round(v,3) for k,v in family_scores.items()},
                "indicator_votes":details,"indicators":{
                    "ema":{"9":e9,"21":e21,"50":e50},"sma":{"20":s20,"50":s50},
                    "rsi":r,"macd":m,"bollinger":bb,"stoch_rsi":st,"adx_dmi":ad,
                    "ichimoku":ichi,"atr":a,"vwap":vw,"obv":obv,"mfi":mfi,
                    "divergence":div,"smc":structure,"taker_flow":strength}}
    except (KeyError,TypeError,ValueError,ZeroDivisionError) as exc:
        return {"valid":False,"reason":f"Invalid candle data: {exc}"}


def _levels(side, price, volatility):
    risk=max(volatility*1.5,price*0.001)
    if side=="LONG":
        return {"entry":price,"stop_loss":price-risk,
                "take_profits":[price+risk,price+2*risk,price+3*risk]}
    return {"entry":price,"stop_loss":price+risk,
            "take_profits":[price-risk,price-2*risk,price-3*risk]}


def analyze_market(timeframes: Dict[str,List[Dict[str,Any]]]) -> Dict[str,Any]:
    """Final decision is based on all indicator families and 5m/15m/30m alignment."""
    reports={tf:analyze_timeframe(timeframes.get(tf,[])) for tf in REQUIRED_TFS}
    invalid=[tf for tf,r in reports.items() if not r.get("valid")]
    if invalid:
        return {"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"Insufficient/invalid candles: "+", ".join(invalid),
                "timeframes":reports,"trade_enabled":False}
    weights={"5m":0.25,"15m":0.35,"30m":0.40}
    combined=sum(reports[tf]["score"]*weights[tf] for tf in REQUIRED_TFS)
    longs=sum(reports[tf]["bias"]=="bullish" for tf in REQUIRED_TFS)
    shorts=sum(reports[tf]["bias"]=="bearish" for tf in REQUIRED_TFS)
    if combined>=28 and longs==3: signal="LONG"
    elif combined<=-28 and shorts==3: signal="SHORT"
    else: signal="NO_SIGNAL"
    confidence=min(99,round(abs(combined)*0.75+max(longs,shorts)*8,1))
    result={"signal":signal,"status":signal,"confidence_score":confidence,
            "combined_score":round(combined,2),"timeframes":reports,
            "timeframe_weights":weights,"trade_enabled":False,
            "note":"Read-only analysis; confidence is a model score, not a probability or guarantee."}
    if signal in ("LONG","SHORT"):
        a=reports["5m"]["indicators"]["atr"]; p=reports["5m"]["close"]
        if not a or a<=0: return {**result,"signal":"NO_SIGNAL","status":"NO_SIGNAL","reason":"ATR unavailable"}
        result.update(_levels(signal,p,a))
    else:
        result["reason"]="Multi-timeframe indicator consensus not strong enough"
    return result


def generate_signal(timeframes, mode="auto"):
    """Compatibility alias. This engine always uses the required 5m/15m/30m model."""
    if mode not in ("auto","trend","scalp"):
        raise ValueError("mode must be auto, scalp or trend")
    return analyze_market(timeframes)
