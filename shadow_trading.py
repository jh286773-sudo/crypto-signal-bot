"""Persistent, isolated shadow-trading ledger used only to train expectancy gates.

No exchange orders are placed. Intrabar ambiguity is resolved conservatively:
if a candle touches both stop and target, the stop is processed first.
"""
from __future__ import annotations

import json
import os
import sqlite3
from contextlib import contextmanager
from typing import Any


class ShadowLedger:
    def __init__(self, path: str | None = None, fee_and_slippage_per_side: float = 0.0007):
        self.path = path or os.getenv("AURUM_SHADOW_DB", "aurum_shadow_trades.sqlite3")
        self.cost = float(fee_and_slippage_per_side)
        if not 0 <= self.cost < 0.02:
            raise ValueError("invalid shadow-trading per-side cost")
        with self._db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS shadow_open (
                symbol TEXT PRIMARY KEY, state TEXT NOT NULL, last_bar INTEGER NOT NULL)""")
            db.execute("""CREATE TABLE IF NOT EXISTS shadow_closed (
                id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL,
                side TEXT NOT NULL, net_r REAL NOT NULL, closed_at INTEGER NOT NULL)""")
            db.execute("""CREATE TABLE IF NOT EXISTS shadow_cursor (
                symbol TEXT PRIMARY KEY, last_bar INTEGER NOT NULL)""")

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path, timeout=20)
        try:
            db.execute("PRAGMA journal_mode=WAL")
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _bar_time(bar: dict[str, Any]) -> int:
        for key in ("timestamp", "open_time", "time"):
            if key in bar:
                return int(bar[key])
        raise ValueError("shadow candle has no timestamp")

    def stats(self, symbol: str, limit: int = 200) -> dict[str, list[float]]:
        with self._db() as db:
            rows = db.execute(
                "SELECT side, net_r FROM shadow_closed WHERE symbol=? ORDER BY id ASC",
                (symbol,),
            ).fetchall()
        result = {"LONG": [], "SHORT": []}
        for side, value in rows:
            if side in result:
                result[side].append(float(value))
                result[side] = result[side][-max(1, int(limit)):]
        return result

    def process(self, symbol: str, bar: dict[str, Any], candidate: dict[str, Any]) -> None:
        stamp = self._bar_time(bar)
        with self._db() as db:
            cursor = db.execute("SELECT last_bar FROM shadow_cursor WHERE symbol=?", (symbol,)).fetchone()
            if cursor and stamp <= int(cursor[0]):
                return
            db.execute(
                "INSERT INTO shadow_cursor(symbol,last_bar) VALUES(?,?) "
                "ON CONFLICT(symbol) DO UPDATE SET last_bar=excluded.last_bar",
                (symbol, stamp),
            )
            row = db.execute("SELECT state FROM shadow_open WHERE symbol=?", (symbol,)).fetchone()
            closed_this_bar = False
            if row:
                state = json.loads(row[0])
                closed_this_bar = self._manage(db, symbol, state, bar, stamp)
            if not row or closed_this_bar:
                if closed_this_bar:
                    return
                side = candidate.get("signal")
                entry = candidate.get("entry")
                stop = candidate.get("stop_loss")
                targets = candidate.get("take_profits") or []
                try:
                    entry, stop = float(entry), float(stop)
                    targets = [float(x) for x in targets[:3]]
                except (TypeError, ValueError):
                    return
                if side not in ("LONG", "SHORT") or len(targets) != 3:
                    return
                ordered = stop < entry < targets[0] < targets[1] < targets[2] if side == "LONG" else stop > entry > targets[0] > targets[1] > targets[2]
                if not ordered or entry <= 0:
                    return
                risk = abs(entry - stop)
                if risk <= 0:
                    return
                state = {
                    "side": side, "entry": entry, "initial_risk": risk, "stop": stop,
                    "targets": targets, "remaining": 1.0, "gross_r": 0.0,
                    "entry_cost_r": entry * self.cost / risk, "exit_cost_r": 0.0,
                    "tp1_done": False, "tp2_done": False, "opened_at": stamp,
                }
                db.execute("INSERT OR REPLACE INTO shadow_open(symbol,state,last_bar) VALUES(?,?,?)",
                           (symbol, json.dumps(state, separators=(",", ":")), stamp))

    def _manage(self, db, symbol: str, state: dict[str, Any], bar: dict[str, Any], stamp: int) -> bool:
        side = state["side"]
        long = side == "LONG"
        op, high, low = (float(bar[k]) for k in ("open", "high", "low"))
        stop = float(state["stop"])
        stop_hit = low <= stop if long else high >= stop
        if stop_hit:
            fill = min(op, stop) if long else max(op, stop)
            self._fill(db, symbol, state, fill, float(state["remaining"]), stamp)
            return True
        for index, target in enumerate(state["targets"]):
            if index == 0 and state["tp1_done"] or index == 1 and state["tp2_done"]:
                continue
            hit = high >= target if long else low <= target
            if not hit:
                break
            remaining = float(state["remaining"])
            fraction = remaining if index == 2 else min(remaining, 1.0 / 3.0)
            fill = max(op, target) if long else min(op, target)
            if index == 0:
                state["tp1_done"] = True
                state["stop"] = float(state["entry"])
            elif index == 1:
                state["tp2_done"] = True
                state["stop"] = float(state["targets"][0])
            self._fill(db, symbol, state, fill, fraction, stamp, persist=False)
            if state["remaining"] <= 1e-9:
                return True
            # If TP1/TP2 and the newly protected stop are both touched in this
            # OHLC candle, assume target first then stop: conservative for the remainder.
            protected_stop = float(state["stop"])
            protected_stop_hit = low <= protected_stop if long else high >= protected_stop
            if index < 2 and protected_stop_hit:
                protected_fill = min(op, protected_stop) if long else max(op, protected_stop)
                self._fill(db, symbol, state, protected_fill, float(state["remaining"]), stamp, persist=False)
                return True
            if index < 2:
                break
        if stamp - int(state.get("opened_at", stamp)) >= 72 * 300000:
            self._fill(db, symbol, state, float(bar["close"]), float(state["remaining"]), stamp)
            return True
        db.execute("UPDATE shadow_open SET state=?, last_bar=? WHERE symbol=?",
                   (json.dumps(state, separators=(",", ":")), stamp, symbol))
        return False

    def _fill(self, db, symbol, state, price: float, fraction: float, stamp: int, persist: bool = True):
        direction = 1.0 if state["side"] == "LONG" else -1.0
        risk = float(state["initial_risk"])
        state["gross_r"] += direction * (price - float(state["entry"])) / risk * fraction
        state["exit_cost_r"] += price * self.cost / risk * fraction
        state["remaining"] = max(0.0, float(state["remaining"]) - fraction)
        if state["remaining"] <= 1e-9:
            self._save_closed(db, symbol, state, stamp)
        elif persist:
            db.execute("UPDATE shadow_open SET state=?, last_bar=? WHERE symbol=?",
                       (json.dumps(state, separators=(",", ":")), stamp, symbol))

    def _save_closed(self, db, symbol, state, stamp):
        net_r = float(state["gross_r"]) - float(state["entry_cost_r"]) - float(state["exit_cost_r"])
        if net_r == net_r and abs(net_r) != float("inf"):
            db.execute("INSERT INTO shadow_closed(symbol,side,net_r,closed_at) VALUES(?,?,?,?)",
                       (symbol, state["side"], net_r, stamp))
        db.execute(
            "DELETE FROM shadow_closed WHERE symbol=? AND side=? AND id NOT IN "
            "(SELECT id FROM shadow_closed WHERE symbol=? AND side=? ORDER BY id DESC LIMIT 200)",
            (symbol, state["side"], symbol, state["side"]),
        )
        db.execute("DELETE FROM shadow_open WHERE symbol=?", (symbol,))
