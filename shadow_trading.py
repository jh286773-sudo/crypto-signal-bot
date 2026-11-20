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
        """Open LONG while flat; a SHORT signal only closes that LONG."""
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
            side = candidate.get("signal")
            try:
                entry = float(candidate.get("entry"))
            except (TypeError, ValueError):
                entry = 0.0
            if side not in ("LONG", "SHORT") or entry <= 0:
                return

            row = db.execute("SELECT state FROM shadow_open WHERE symbol=?", (symbol,)).fetchone()
            if row:
                state = json.loads(row[0])
                old_side = state.get("side")
                if old_side == "LONG" and side == "LONG":
                    return
                if old_side == "SHORT" and side == "SHORT":
                    return
                old_entry = float(state.get("entry", 0.0))
                if old_side in ("LONG", "SHORT") and old_entry > 0:
                    direction = 1.0 if old_side == "LONG" else -1.0
                    net_return = direction * (entry - old_entry) / old_entry - 2.0 * self.cost
                    if net_return == net_return and abs(net_return) != float("inf"):
                        db.execute(
                            "INSERT INTO shadow_closed(symbol,side,net_r,closed_at) VALUES(?,?,?,?)",
                            (symbol, old_side, net_return, stamp),
                        )
                        db.execute(
                            "DELETE FROM shadow_closed WHERE symbol=? AND side=? AND id NOT IN "
                            "(SELECT id FROM shadow_closed WHERE symbol=? AND side=? ORDER BY id DESC LIMIT 200)",
                            (symbol, old_side, symbol, old_side),
                        )
                db.execute("DELETE FROM shadow_open WHERE symbol=?", (symbol,))
                # SHORT is an exit instruction only. Never reverse into a short.
                if old_side == "LONG" and side == "SHORT":
                    return

            # Ignore SHORT when flat; only a LONG can establish a position.
            if side != "LONG":
                return
            state = {"side": "LONG", "entry": entry, "opened_at": stamp}
            db.execute(
                "INSERT OR REPLACE INTO shadow_open(symbol,state,last_bar) VALUES(?,?,?)",
                (symbol, json.dumps(state, separators=(",", ":")), stamp),
            )
