#!/usr/bin/env python3
"""Lecture seule : pousse les fills IBKR vers le miroir P&L de hermes-web.

Un seul cycle par exécution (timer systemd horaire). Aucun ordre n'est jamais envoyé : ce script lit les
exécutions du Gateway et écrit dans le hub, rien d'autre.

Contrat : POST {HERMES_WEB_URL}/api/trading/pnl-import avec broker=ibkr.
- Identité de ligne : fill_ref = execId IBKR (donc idempotent côté hub).
- Un close est une exécution qui réduit une position, appariée en FIFO par contrat.
- P&L natif = (sortie − entrée) × multiplicateur × sens − commissions des deux jambes.
- Le multiplicateur vient de reqContractDetails, jamais d'une table devinée. Introuvable ⇒ ligne refusée.
- Devise : pl = CAD, pl_native = devise de l'instrument, currency = cette devise. Le taux USD→CAD utilisé est
  stocké dans exit_reason (`FX <rate> USD_CAD <instant>`) pour être reproductible.
- orderRef nomme la stratégie quand il en porte une, sinon la ligne est un leak (un ordre manuel ou un smoke
  test n'est pas une stratégie).

Usage:
    ibkr_pnl_push.py [--dry-run] [--selftest] [--url URL] [--timeout S] [--since YYYY-MM-DD]

Env (fichier HERMES_IBKR_ENV, défaut ~/.config/hermes-ibkr/publisher.env):
    IBKR_GATEWAY_HOST, IBKR_GATEWAY_PORT, IBKR_ACCOUNT_ID,
    HERMES_IBKR_PNL_CLIENT_ID (98), HERMES_WEB_URL, HERMES_WEB_TOKEN
    OANDA_API_KEY (lue dans ~/projects/trading-bridge/.env.paper si absente de l'env)
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

SOURCE = "hermes-ibkr-pnl"
PNL_PATH = "/api/trading/pnl-import"
WINDOW_START = "2026-10-01T00:00:00Z"
EXCHANGE_TZ = ZoneInfo("America/New_York")   # les heures d'exécution IBKR sont à l'heure du Gateway
UNAVAILABLE = 1.7976931348623157e308


def load_env_file(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.split("#")[0].strip().strip('"').strip("'"))


def oanda_key() -> str | None:
    """Cle du compte paper, lue dans le fichier AVANT l'environnement.

    Le shell Hermes exporte des OANDA_* qui ne pointent pas sur le meme compte : s'en servir ici donnerait un
    401 (mesure) alors que la cle du fichier fonctionne. Le fichier est la reference, l'environnement un repli.
    """
    env = Path.home() / "projects/trading-bridge/.env.paper"
    if env.exists():
        for line in env.read_text().splitlines():
            if line.strip().startswith("OANDA_API_KEY="):
                return line.split("=", 1)[1].split("#")[0].strip().strip('"').strip("'")
    return os.environ.get("OANDA_API_KEY") or None


# --------------------------------------------------------------------------- FIFO


class FifoBook:
    """Appariement FIFO par conId, en unités signées. Aucune hypothèse sur le sens d'ouverture."""

    def __init__(self) -> None:
        self.lots: dict[int, deque] = defaultdict(deque)

    def apply(self, con_id: int, qty: float, price: float, fee: float) -> dict | None:
        """Enregistre une exécution. Retourne le réalisé si elle ferme, None si elle ouvre."""
        book = self.lots[con_id]
        remaining = qty
        realized = 0.0
        fees_matched = 0.0
        matched_qty = 0.0
        entry_price = None
        while remaining != 0 and book and (book[0]["qty"] > 0) != (remaining > 0):
            lot = book[0]
            take = min(abs(lot["qty"]), abs(remaining))
            direction = 1.0 if lot["qty"] > 0 else -1.0
            realized += (price - lot["price"]) * take * direction
            if entry_price is None:
                entry_price = lot["price"]
            part = take / abs(lot["qty"])
            fees_matched += lot["fee"] * part
            lot["fee"] -= lot["fee"] * part
            lot["qty"] -= take * direction
            matched_qty += take
            remaining += take * (1.0 if remaining < 0 else -1.0)
            if abs(lot["qty"]) < 1e-12:
                book.popleft()
        if abs(remaining) > 1e-12:
            # ouverture (ou retournement) : la portion non appariée devient un lot
            book.append({"qty": remaining, "price": price, "fee": fee * (abs(remaining) / abs(qty))})
        if entry_price is None:
            return None
        closing_fee = fee * (matched_qty / abs(qty)) if qty else 0.0
        return {"realized": realized, "fees": fees_matched + closing_fee, "entry_price": entry_price}


# --------------------------------------------------------------------------- FX


class FxRates:
    """USD→CAD à l'instant du fill, via la clôture H1 OANDA. Cache par heure."""

    def __init__(self, key: str | None) -> None:
        self.key = key
        self.cache: dict[str, float] = {}
        self.errors: list[str] = []

    def rate(self, instant: datetime) -> float | None:
        bucket = instant.strftime("%Y-%m-%dT%H")
        if bucket in self.cache:
            return self.cache[bucket]
        if not self.key:
            self.errors.append("OANDA_API_KEY absente : conversion USD→CAD impossible")
            return None
        start = instant.replace(minute=0, second=0, microsecond=0)
        # OANDA refuse `count` combine a from/to : on borne la fenetre et on garde la derniere bougie.
        query = urllib.parse.urlencode({
            "granularity": "H1",
            "from": start.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "to": (start + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "price": "M",
        })
        req = urllib.request.Request(
            "https://api-fxpractice.oanda.com/v3/instruments/USD_CAD/candles?" + query,
            headers={"Authorization": "Bearer " + self.key},
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                candles = json.load(response).get("candles", [])
        except Exception as exc:                     # noqa: BLE001 - réseau : la ligne sera refusée
            self.errors.append(f"FX {bucket}: {exc}")
            return None
        if not candles:
            self.errors.append(f"FX {bucket}: aucune bougie")
            return None
        mid = float(candles[-1]["mid"]["c"])
        self.cache[bucket] = mid
        return mid


# --------------------------------------------------------------------------- payload


def parse_ibkr_time(raw: str) -> datetime:
    """'20261021 05:56:48' (heure Gateway) → datetime UTC."""
    cleaned = " ".join(raw.split())
    local = datetime.strptime(cleaned, "%Y%m%d %H:%M:%S").replace(tzinfo=EXCHANGE_TZ)
    return local.astimezone(timezone.utc)


def build_payload(account_id: str, executions: list[dict], snapshot: dict, fx: FxRates,
                  since_utc: datetime) -> tuple[dict, list[str]]:
    """Construit le payload de la lane IBKR. Retourne (payload, refus)."""
    refused: list[str] = []
    fifo = FifoBook()
    entries: list[dict] = []

    for ex in sorted(executions, key=lambda e: (e["time"], e["order_id"], e["exec_id"])):
        closed_at = parse_ibkr_time(ex["time"])
        if closed_at < since_utc:
            continue
        multiplier = ex.get("multiplier")
        if multiplier is None:
            refused.append(f"{ex['exec_id']}: multiplicateur introuvable pour conId {ex['con_id']}")
            continue
        qty = ex["shares"] if ex["side"] == "BOT" else -ex["shares"]
        result = fifo.apply(ex["con_id"], qty, ex["price"], ex.get("commission", 0.0))
        if result is None:
            continue                                  # ouverture : le miroir enregistre de l'argent
        native_ccy = ex.get("currency") or snapshot.get("currency") or "USD"
        native = result["realized"] * multiplier - result["fees"]
        rate = 1.0
        fx_note = "FX native (devise du compte)"
        if native_ccy != snapshot.get("currency", "CAD"):
            fetched = fx.rate(closed_at)
            if fetched is None:
                refused.append(f"{ex['exec_id']}: taux {native_ccy}→CAD introuvable, ligne refusée")
                continue
            rate = fetched
            fx_note = f"FX {rate:.6f} {native_ccy}_CAD {closed_at.strftime('%Y-%m-%dT%H:%M:%SZ')}"

        order_ref = (ex.get("order_ref") or "").strip()
        tag = order_ref or f"IBKR-ORDER-{ex['order_id']}"
        is_strategy = bool(order_ref) and order_ref.replace("-", "").replace("_", "").isalnum()
        local = closed_at.astimezone(ZoneInfo("America/Montreal"))

        entries.append({
            "fill_ref": ex["exec_id"],
            "kind": "strategy" if is_strategy else "leak",
            "strategy": order_ref if is_strategy else None,
            "tag": tag,
            "instrument": ex["symbol"],
            "direction": "BUY" if qty > 0 else "SELL",
            "units": abs(qty),
            "entry_price": result["entry_price"],
            "exit_price": ex["price"],
            "pl": round(native * rate, 4),
            "pl_native": round(native, 4),
            "currency": native_ccy,
            "commission": round(result["fees"], 4),
            "financing": 0.0,
            # exit_reason est borne a 32 caracteres par l'API : le taux garde sa place, le bavardage non.
            "exit_reason": (f"IBKR FX {rate:.6f}" if rate != 1.0 else "IBKR"),
            "order_id": str(ex["order_id"]),
            "opened_at_utc": None,
            "closed_at_utc": closed_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "local_date": local.strftime("%Y-%m-%d"),
            "iso_week": "%s-W%02d" % local.isocalendar()[:2],
            "iso_month": local.strftime("%Y-%m"),
            "iso_year": local.strftime("%Y"),
            "dow": local.strftime("%a"),
            "hour_local": local.hour,
        })

    payload = {
        "broker": "ibkr",
        "account_id": account_id,
        "environment": "practice",
        "window_start_utc": WINDOW_START,
        "snapshot": snapshot,
        "entries": entries,
    }
    return payload, refused


# --------------------------------------------------------------------------- gateway


def collect(host: str, port: int, client_id: int, since: datetime, timeout: int) -> dict:
    from ibapi.client import EClient
    from ibapi.contract import Contract
    from ibapi.execution import ExecutionFilter
    from ibapi.wrapper import EWrapper

    class Probe(EWrapper, EClient):
        def __init__(self) -> None:
            EClient.__init__(self, self)
            self.ready = threading.Event()
            self.exec_done = threading.Event()
            self.summary_done = threading.Event()
            self.pos_done = threading.Event()
            self.cd_done = threading.Event()
            self.executions: list[dict] = []
            self.commissions: dict[str, float] = {}
            self.summary: dict[str, str] = {}
            self.positions = 0
            self.multipliers: dict[int, float] = {}
            self.pending_cd: list[int] = []
            self.errors: list[str] = []

        def nextValidId(self, orderId: int) -> None:      # noqa: N802 - API ibapi
            self.ready.set()

        def error(self, reqId, code, msg, advancedOrderRejectJson=""):  # noqa: N802
            if code not in (2104, 2106, 2107, 2158, 2100, 2108, 2119):
                self.errors.append(f"{code}: {msg}")

        def execDetails(self, reqId, contract, execution):  # noqa: N802
            self.executions.append({
                "exec_id": execution.execId,
                "time": execution.time,
                "side": execution.side,
                "shares": float(execution.shares),
                "price": float(execution.price),
                "order_id": execution.orderId,
                "order_ref": getattr(execution, "orderRef", "") or "",
                "currency": getattr(contract, "currency", "") or "",
                "con_id": int(getattr(contract, "conId", 0) or 0),
                "symbol": getattr(contract, "localSymbol", "") or getattr(contract, "symbol", "") or "",
                "multiplier": None,
                "liquidity": "",
            })

        def execDetailsEnd(self, reqId):  # noqa: N802
            self.exec_done.set()

        def commissionReport(self, report):  # noqa: N802
            self.commissions[report.execId] = float(report.commission)

        def accountSummary(self, reqId, account, tag, value, currency):  # noqa: N802
            self.summary[tag] = value

        def accountSummaryEnd(self, reqId):  # noqa: N802
            self.summary_done.set()

        def position(self, account, contract, position, avgCost):  # noqa: N802
            if abs(float(position)) > 1e-9:
                self.positions += 1

        def positionEnd(self):  # noqa: N802
            self.pos_done.set()

        def contractDetails(self, reqId, details):  # noqa: N802
            try:
                self.multipliers[int(details.contract.conId)] = float(details.contract.multiplier)
            except (TypeError, ValueError):
                pass

        def contractDetailsEnd(self, reqId):  # noqa: N802
            self.cd_done.set()

    client = Probe()
    try:
        # ibapi 9.81 : connect() ouvre la session, et c'est run() qui traite les messages entrants. Le thread
        # lecteur n'est pas daemon, donc toute sortie doit passer par disconnect(), sinon le process reste
        # bloque a l'arret (mesure : handle de 40 s sur recvMsg).
        client.connect(host, port, clientId=client_id)
        threading.Thread(target=client.run, daemon=True).start()
        if not client.ready.wait(min(10, timeout)):
            raise TimeoutError("pas de nextValidId")

        client.reqAccountSummary(1, "All", "NetLiquidation,TotalCashValue,UnrealizedPnL,RealizedPnL,Currency")
        client.reqPositions()
        flt = ExecutionFilter()
        flt.time = since.astimezone(EXCHANGE_TZ).strftime("%Y%m%d-%H:%M:%S")
        client.reqExecutions(2, flt)

        client.exec_done.wait(timeout)
        client.summary_done.wait(timeout)
        client.pos_done.wait(timeout)

        con_ids = sorted({e["con_id"] for e in client.executions if e["con_id"]})
        for con_id in con_ids:
            contract = Contract()
            contract.conId = con_id
            client.reqContractDetails(1000 + con_id % 100000, contract)
            client.cd_done.wait(timeout)
            client.cd_done.clear()

        for ex in client.executions:
            ex["multiplier"] = client.multipliers.get(ex["con_id"])
            ex["commission"] = client.commissions.get(ex["exec_id"], 0.0)
    finally:
        try:
            client.disconnect()
        except Exception:                        # noqa: BLE001 - la deconnexion ne masque pas l'erreur
            pass

    def num(tag: str) -> float | None:
        raw = client.summary.get(tag)
        if raw is None:
            return None
        value = float(raw)
        return None if abs(value) > 1e290 else value

    snapshot = {
        "nav": num("NetLiquidation"),
        "balance": num("TotalCashValue"),
        "unrealized_pl": num("UnrealizedPnL"),
        "open_trade_count": client.positions,
        "currency": client.summary.get("Currency") or "CAD",
        "extraction_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": SOURCE,
    }
    return {"executions": client.executions, "snapshot": snapshot, "errors": client.errors}


# --------------------------------------------------------------------------- IO


def post(url: str, token: str, body: dict, timeout: int = 30) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode()


def selftest() -> int:
    checks: dict[str, bool] = {}
    book = FifoBook()
    assert book.apply(1, 1.0, 7766.50, 0.61) is None, "une ouverture ne réalise rien"
    closed = book.apply(1, -1.0, 7766.25, 0.61)
    checks["aller-retour: réalisé"] = closed is not None and abs(closed["realized"] + 0.25) < 1e-9
    checks["aller-retour: commissions des deux jambes"] = closed is not None and abs(closed["fees"] - 1.22) < 1e-9

    book2 = FifoBook()
    book2.apply(2, 2.0, 100.0, 0.0)
    half = book2.apply(2, -1.0, 110.0, 0.0)
    checks["close partiel"] = half is not None and abs(half["realized"] - 10.0) < 1e-9
    rest = book2.apply(2, -1.0, 90.0, 0.0)
    checks["second close du même lot"] = rest is not None and abs(rest["realized"] + 10.0) < 1e-9
    checks["lot épuisé"] = len(book2.lots[2]) == 0

    book3 = FifoBook()
    book3.apply(3, -1.0, 50.0, 0.0)          # short
    flip = book3.apply(3, 2.0, 40.0, 0.0)    # ferme le short et ouvre un long
    checks["retournement"] = flip is not None and abs(flip["realized"] - 10.0) < 1e-9
    checks["retournement: reste un lot long"] = len(book3.lots[3]) == 1 and book3.lots[3][0]["qty"] == 1.0

    if not all(checks.values()):
        for name, ok in checks.items():
            print(("✓" if ok else "✗"), name)
    print(json.dumps({"selftest": "ok" if all(checks.values()) else "echec", "checks": checks}))
    return 0 if all(checks.values()) else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Pousse les fills IBKR vers le miroir P&L.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--url")
    parser.add_argument("--timeout", type=int, default=25)
    parser.add_argument("--since", default="2026-10-01")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    load_env_file(Path(os.environ.get("HERMES_IBKR_ENV", Path.home() / ".config/hermes-ibkr/publisher.env")))

    account = os.environ.get("IBKR_ACCOUNT_ID", "")
    host = os.environ.get("IBKR_GATEWAY_HOST", "127.0.0.1")
    port = int(os.environ.get("IBKR_GATEWAY_PORT", "4002"))
    client_id = int(os.environ.get("HERMES_IBKR_PNL_CLIENT_ID", "98"))
    base = args.url or os.environ.get("HERMES_WEB_URL", "")
    token = os.environ.get("HERMES_WEB_TOKEN", "")
    since = datetime.fromisoformat(args.since).replace(tzinfo=timezone.utc)

    if not account:
        print("IBKR_ACCOUNT_ID requis", file=sys.stderr)
        return 2
    if not args.dry_run and (not base or not token):
        print("HERMES_WEB_URL et HERMES_WEB_TOKEN requis", file=sys.stderr)
        return 2

    try:
        data = collect(host, port, client_id, since, args.timeout)
    except Exception as exc:                     # noqa: BLE001 - Gateway mort : on publie l'échec
        print(f"Gateway: {exc}", file=sys.stderr)
        if args.dry_run:
            return 1
        degraded = {
            "broker": "ibkr",
            "account_id": account,
            "environment": "practice",
            "window_start_utc": WINDOW_START,
            "snapshot": {
                "extraction_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "currency": "CAD",
                "source": SOURCE + " (degraded)",
            },
            "entries": [],
        }
        status, body = post(base + PNL_PATH, token, degraded)
        print(f"etat degrade publie: HTTP {status} {body[:200]}", file=sys.stderr)
        return 1

    fx = FxRates(oanda_key())
    payload, refused = build_payload(account, data["executions"], data["snapshot"], fx, since)

    if args.dry_run:
        print(json.dumps({"entries": payload["entries"], "refused": refused,
                          "fx_errors": fx.errors, "gateway_errors": data["errors"]}, indent=1, default=str))
        return 0

    status, body = post(base + PNL_PATH, token, payload)
    print(json.dumps({"http": status, "response": body[:300], "sent": len(payload["entries"]),
                      "refused": refused, "fx_errors": fx.errors, "gateway_errors": data["errors"]}))
    return 0 if 200 <= status < 300 else 1


if __name__ == "__main__":
    sys.exit(main())
