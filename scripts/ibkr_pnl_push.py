#!/usr/bin/env python3
"""Lecture seule : pousse les fills fermés IBKR vers le miroir P&L de hermes-web.

Un seul cycle par exécution (timer systemd horaire). Aucun ordre n'est jamais envoyé.

Le P&L vient de la SOURCE, pas d'un calcul maison. IBKR renseigne `CommissionReport.realizedPNL` sur chaque
exécution qui ferme, déjà net des commissions des deux jambes, et renvoie sa sentinelle (1.797e308) sur une
ouverture. Mesuré sur le compte papier : ouverture = sentinelle, close = -2.47 USD pour 1 MES à -0.25 point,
soit exactement -1.25 de mouvement moins 1.22 de commissions. Conséquence : aucun appariement FIFO, aucun
multiplicateur à deviner, aucune commission à re-soustraire, et aucun historique à reconstituer.

Contrat : POST {HERMES_WEB_URL}/api/trading/pnl-import avec broker=ibkr.
- Identité de ligne : fill_ref = execId IBKR (idempotent). Une correction IBKR arrive avec le dernier segment
  d'execId incrémenté : seule la révision la plus haute est gardée, sinon la quantité et le P&L comptent double.
- Compte : refus de publier si le Gateway n'est pas connecté au compte configuré (managedAccounts), et
  exécutions, positions et résumé sont filtrés sur ce compte. Un compte live (U…) exige
  HERMES_IBKR_ENVIRONMENT=live, sinon le script s'arrête.
- Devise : `pl_native` = realizedPNL dans la devise du rapport, `pl` = CAD au taux {devise}_CAD clôture H1 OANDA
  de l'heure du fill, stocké sur la ligne (exit_reason « IBKR FX 1.403980 »). Bougie absente ou incomplète,
  devise non convertible ⇒ ligne refusée, jamais convertie au hasard.
- `orderRef` nomme la stratégie quand il en porte une, sinon la ligne est un leak.
- Gateway muet, partiel ou en erreur : payload dégradé publié ET code de sortie non nul.

Usage:
    ibkr_pnl_push.py [--dry-run] [--selftest] [--url URL] [--timeout S]

Env (HERMES_IBKR_ENV, défaut ~/.config/hermes-ibkr/publisher.env) :
    IBKR_GATEWAY_HOST, IBKR_GATEWAY_PORT, IBKR_ACCOUNT_ID, HERMES_IBKR_PNL_CLIENT_ID (98),
    HERMES_WEB_URL, HERMES_WEB_TOKEN, HERMES_IBKR_ENVIRONMENT (practice|live, déduit de DU… sinon)
    OANDA_API_KEY : lue dans ~/projects/trading-bridge/.env.paper (le shell peut porter une clé d'un autre compte).
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

SOURCE = "hermes-ibkr-pnl"
PNL_PATH = "/api/trading/pnl-import"
WINDOW_START = "2026-10-01T00:00:00Z"
EXCHANGE_TZ = ZoneInfo("America/New_York")     # les heures d'exécution IBKR sont à l'heure du Gateway
MONTREAL = ZoneInfo("America/Montreal")
UNAVAILABLE = 1.7976931348623157e308
BENIGN_ERRORS = (2104, 2106, 2107, 2158, 2100, 2108, 2119)


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
    """Clé du compte paper, lue dans le fichier AVANT l'environnement (le shell porte une autre clé : 401)."""
    env = Path.home() / "projects/trading-bridge/.env.paper"
    if env.exists():
        for line in env.read_text().splitlines():
            if line.strip().startswith("OANDA_API_KEY="):
                return line.split("=", 1)[1].split("#")[0].strip().strip('"').strip("'")
    return os.environ.get("OANDA_API_KEY") or None


# --------------------------------------------------------------------------- exécutions


REVISION = re.compile(r"^(.*)\.(\d+)$")


def base_exec_id(exec_id: str) -> tuple[str, int]:
    """'a.b.01.03' → ('a.b.01', 3). Une correction IBKR remplace la ligne par une révision plus haute."""
    match = REVISION.match(exec_id)
    if not match:
        return exec_id, 0
    return match.group(1), int(match.group(2))


def latest_revisions(executions: list[dict]) -> list[dict]:
    """Une seule ligne par fill : la révision la plus haute."""
    kept: dict[str, dict] = {}
    for execution in executions:
        key, revision = base_exec_id(execution["exec_id"])
        current = kept.get(key)
        if current is None or revision > base_exec_id(current["exec_id"])[1]:
            kept[key] = execution
    return list(kept.values())


def is_close(report: dict | None) -> bool:
    """IBKR dit lui-même si une exécution ferme : realizedPNL renseigné, sentinelle sinon."""
    if not report:
        return False
    realized = report.get("realized_pnl")
    return realized is not None and math.isfinite(realized) and abs(realized) < 1e290


def parse_ibkr_time(raw: str) -> datetime:
    """'20261021 05:56:48' (heure Gateway) → UTC. Refuse une heure ambiguë (changement d'heure)."""
    cleaned = " ".join(raw.split())
    naive = datetime.strptime(cleaned, "%Y%m%d %H:%M:%S")
    first = naive.replace(tzinfo=EXCHANGE_TZ, fold=0)
    second = naive.replace(tzinfo=EXCHANGE_TZ, fold=1)
    if first.utcoffset() != second.utcoffset():
        raise ValueError(f"heure locale ambiguë (changement d'heure) : {cleaned}")
    return first.astimezone(timezone.utc)


# --------------------------------------------------------------------------- FX


def candle_for(candles: list[dict], hour_start: datetime) -> dict | None:
    """La bougie qui COMMENCE à l'heure du fill et qui est complète. Sinon rien : la ligne sera refusée."""
    wanted = hour_start.strftime("%Y-%m-%dT%H:%M:%S")
    for candle in candles:
        if str(candle.get("time", ""))[:19] == wanted and candle.get("complete") is True:
            return candle
    return None


class FxRates:
    """{devise}→CAD à l'heure du fill, clôture H1 OANDA. Cache par (paire, heure)."""

    def __init__(self, key: str | None) -> None:
        self.key = key
        self.cache: dict[tuple[str, str], float] = {}
        self.errors: list[str] = []

    def rate(self, currency: str, instant: datetime) -> float | None:
        pair = f"{currency}_CAD"
        hour_start = instant.replace(minute=0, second=0, microsecond=0)
        cache_key = (pair, hour_start.isoformat())
        if cache_key in self.cache:
            return self.cache[cache_key]
        if not self.key:
            self.errors.append("OANDA_API_KEY absente : conversion impossible")
            return None
        query = urllib.parse.urlencode({
            "granularity": "H1",
            "from": (hour_start - timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "to": (hour_start + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "price": "M",
        })
        request = urllib.request.Request(
            f"https://api-fxpractice.oanda.com/v3/instruments/{pair}/candles?" + query,
            headers={"Authorization": "Bearer " + self.key},
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                candles = json.load(response).get("candles", [])
        except Exception as exc:                     # noqa: BLE001 - réseau : la ligne sera refusée
            self.errors.append(f"{pair} {hour_start:%Y-%m-%dT%H}: {exc}")
            return None
        candle = candle_for(candles, hour_start)
        if candle is None:
            self.errors.append(f"{pair} {hour_start:%Y-%m-%dT%H}: aucune bougie H1 complète à cette heure")
            return None
        self.cache[cache_key] = float(candle["mid"]["c"])
        return self.cache[cache_key]


# --------------------------------------------------------------------------- payload


def build_payload(account_id: str, environment: str, executions: list[dict],
                  snapshot: dict, fx: FxRates) -> tuple[dict, list[str]]:
    """Construit le payload de la lane IBKR. Retourne (payload, lignes refusées)."""
    refused: list[str] = []
    entries: list[dict] = []
    account_currency = snapshot.get("currency") or "CAD"

    for execution in sorted(latest_revisions(executions), key=lambda e: (e["time"], e["exec_id"])):
        report = execution.get("report") or {}
        if not is_close(report):
            continue                                  # ouverture : le miroir enregistre de l'argent
        try:
            closed_at = parse_ibkr_time(execution["time"])
        except (ValueError, TypeError) as exc:
            refused.append(f"{execution['exec_id']}: {exc}")
            continue

        native = float(report["realized_pnl"])       # déjà net des commissions des deux jambes
        currency = (report.get("currency") or execution.get("currency") or "").upper()
        if not currency:
            refused.append(f"{execution['exec_id']}: devise du rapport inconnue")
            continue

        rate = 1.0
        if currency != account_currency:
            fetched = fx.rate(currency, closed_at)
            if fetched is None:
                refused.append(f"{execution['exec_id']}: taux {currency}→{account_currency} introuvable, "
                               f"ligne refusée")
                continue
            rate = fetched

        order_ref = (execution.get("order_ref") or "").strip()
        tag = order_ref or f"IBKR-ORDER-{execution['order_id']}"
        is_strategy = bool(order_ref) and order_ref.replace("-", "").replace("_", "").isalnum()
        local = closed_at.astimezone(MONTREAL)

        entries.append({
            "fill_ref": execution["exec_id"],
            "kind": "strategy" if is_strategy else "leak",
            "strategy": order_ref if is_strategy else None,
            "tag": tag,
            "instrument": execution["symbol"] or str(execution["con_id"]),
            "direction": "BUY" if execution["side"] == "BOT" else "SELL",
            "units": execution["shares"],
            # Le prix d'entrée n'est pas connu de l'exécution de sortie : on ne l'invente pas.
            "entry_price": None,
            "exit_price": execution["price"],
            "pl": round(native * rate, 4),
            "pl_native": round(native, 4),
            "currency": currency,
            "commission": round(float(report.get("commission") or 0.0), 4),
            "financing": 0.0,
            # exit_reason est borné à 32 caractères par l'API : le taux garde sa place, le bavardage non.
            "exit_reason": (f"IBKR FX {rate:.6f}" if rate != 1.0 else "IBKR"),
            "order_id": str(execution["order_id"]),
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
        "environment": environment,
        "window_start_utc": WINDOW_START,
        "snapshot": snapshot,
        "entries": entries,
    }
    return payload, refused


# --------------------------------------------------------------------------- gateway


def collect(host: str, port: int, client_id: int, account: str, since: datetime, timeout: int) -> dict:
    from ibapi.client import EClient
    from ibapi.execution import ExecutionFilter
    from ibapi.wrapper import EWrapper

    class Probe(EWrapper, EClient):
        def __init__(self) -> None:
            EClient.__init__(self, self)
            self.ready = threading.Event()
            self.accounts_known = threading.Event()
            self.exec_done = threading.Event()
            self.summary_done = threading.Event()
            self.pos_done = threading.Event()
            self.executions: list[dict] = []
            self.reports: dict[str, dict] = {}
            self.summary: dict[str, str] = {}
            self.managed: list[str] = []
            self.positions = 0
            self.errors: list[str] = []

        def nextValidId(self, orderId: int) -> None:      # noqa: N802 - API ibapi
            self.ready.set()

        def error(self, reqId, code, msg, advancedOrderRejectJson=""):  # noqa: N802
            if code not in BENIGN_ERRORS:
                self.errors.append(f"{code}: {msg}")

        def managedAccounts(self, accountsList: str) -> None:  # noqa: N802
            self.managed = [a for a in (accountsList or "").split(",") if a]
            self.accounts_known.set()

        def execDetails(self, reqId, contract, execution):  # noqa: N802
            if execution.acctNumber and execution.acctNumber != account:
                return                                    # un autre compte n'entre jamais ici
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
            })

        def execDetailsEnd(self, reqId):  # noqa: N802
            self.exec_done.set()

        def commissionReport(self, report):  # noqa: N802
            try:
                commission = float(report.commission)
            except (TypeError, ValueError):
                commission = None
            if commission is not None and (not math.isfinite(commission) or abs(commission) > 1e290):
                commission = None
            self.reports[report.execId] = {
                "commission": commission,
                "currency": getattr(report, "currency", "") or "",
                "realized_pnl": float(getattr(report, "realizedPNL", UNAVAILABLE)),
            }

        def accountSummary(self, reqId, acct, tag, value, currency):  # noqa: N802
            if acct and acct != account:
                return
            self.summary[tag] = value

        def accountSummaryEnd(self, reqId):  # noqa: N802
            self.summary_done.set()

        def position(self, acct, contract, position, avgCost):  # noqa: N802
            if acct and acct == account and abs(float(position)) > 1e-9:
                self.positions += 1

        def positionEnd(self):  # noqa: N802
            self.pos_done.set()

    client = Probe()
    try:
        # ibapi 9.81 : connect() ouvre la session, run() traite les messages entrants. Le thread lecteur n'est
        # pas daemon : toute sortie passe par disconnect(), sinon le process reste bloqué (mesuré).
        client.connect(host, port, clientId=client_id)
        threading.Thread(target=client.run, daemon=True).start()
        if not client.ready.wait(min(10, timeout)):
            raise TimeoutError("pas de nextValidId")

        client.reqManagedAccts()
        client.accounts_known.wait(min(5, timeout))
        if account not in client.managed:
            raise ConnectionError(
                f"le Gateway n'est pas connecté au compte {account} (comptes vus : {client.managed or 'aucun'})"
            )

        client.reqAccountSummary(1, "All", "NetLiquidation,TotalCashValue,UnrealizedPnL,RealizedPnL,Currency")
        client.reqPositions()
        flt = ExecutionFilter()
        flt.acctCode = account
        flt.time = since.astimezone(EXCHANGE_TZ).strftime("%Y%m%d-%H:%M:%S")
        client.reqExecutions(2, flt)

        for event, label in ((client.exec_done, "exécutions"), (client.summary_done, "résumé de compte"),
                             (client.pos_done, "positions")):
            if not event.wait(timeout):
                raise TimeoutError(f"{label} non reçues dans le délai")
        if client.errors:
            raise ConnectionError("; ".join(client.errors[:5]))

        for execution in client.executions:
            execution["report"] = client.reports.get(execution["exec_id"])
    finally:
        try:
            client.disconnect()
        except Exception:                        # noqa: BLE001 - la déconnexion ne masque pas l'erreur
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
    return {"executions": client.executions, "snapshot": snapshot}


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

    # Une correction IBKR = même base d'execId, révision plus haute : une seule ligne doit survivre.
    execs = [
        {"exec_id": "a.b.01.01", "time": "20261021 05:56:50", "shares": 1.0, "price": 1.0},
        {"exec_id": "a.b.01.02", "time": "20261021 05:56:50", "shares": 1.0, "price": 2.0},
        {"exec_id": "a.b.01.03", "time": "20261021 05:56:50", "shares": 1.0, "price": 3.0},
    ]
    kept = latest_revisions(execs)
    checks["révisions dédupliquées"] = len(kept) == 1
    checks["révision la plus haute gardée"] = kept[0]["price"] == 3.0

    checks["ouverture non publiée (sentinelle)"] = not is_close({"realized_pnl": UNAVAILABLE})
    # 0.0 est un close à l'équilibre, pas une ouverture : la sentinelle est le seul marqueur d'ouverture
    # (mesuré sur le compte papier : ouverture = 1.797e308, close = -2.47).
    checks["close à zéro publiée"] = is_close({"realized_pnl": 0.0})
    checks["close publiée"] = is_close({"realized_pnl": -2.47})
    checks["rapport absent"] = not is_close(None)

    candles = [
        {"time": "2026-10-21T08:00:00.000000000Z", "complete": True, "mid": {"c": "1.400000"}},
        {"time": "2026-10-21T09:00:00.000000000Z", "complete": True, "mid": {"c": "1.403980"}},
        {"time": "2026-10-21T10:00:00.000000000Z", "complete": True, "mid": {"c": "1.410000"}},
    ]
    hour = datetime(2026, 10, 21, 9, 0, tzinfo=timezone.utc)
    picked = candle_for(candles, hour)
    checks["bougie de l'heure du fill"] = picked is not None and picked["mid"]["c"] == "1.403980"
    checks["bougie incomplète refusée"] = candle_for([dict(candles[1], complete=False)], hour) is None
    checks["heure absente refusée"] = candle_for(candles, datetime(2026, 10, 21, 3, 0, tzinfo=timezone.utc)) is None
    checks["heure Gateway convertie en UTC"] = (
        parse_ibkr_time("20261021  05:56:50").strftime("%Y-%m-%dT%H:%M:%SZ") == "2026-10-21T09:56:50Z"
    )

    execution = {
        "exec_id": "a.b.01.01", "time": "20261021 05:56:50", "side": "SLD", "shares": 1.0, "price": 7766.25,
        "order_id": 1001, "order_ref": "", "symbol": "MESZ6", "con_id": 1,
        "report": {"realized_pnl": -2.47, "currency": "USD", "commission": 0.61},
    }
    payload, refused = build_payload("DU123", "practice", [execution], {"currency": "CAD"}, FxRates(None))
    checks["sans clé FX : ligne refusée, rien d'inventé"] = payload["entries"] == [] and len(refused) == 1

    payload, _ = build_payload("DU123", "practice", [execution], {"currency": "USD"}, FxRates(None))
    entry = payload["entries"][0]
    checks["pl natif = realizedPNL (net des commissions)"] = entry["pl_native"] == -2.47
    checks["sans ordre nommé : leak"] = entry["kind"] == "leak"
    checks["units = taille exécutée"] = entry["units"] == 1.0
    checks["devise du compte : pas de conversion"] = entry["pl"] == -2.47
    checks["prix d'entrée non inventé"] = entry["entry_price"] is None

    if not all(checks.values()):
        for name, ok in checks.items():
            print(("✓" if ok else "✗"), name)
    print(json.dumps({"selftest": "ok" if all(checks.values()) else "echec", "checks": checks}))
    return 0 if all(checks.values()) else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Pousse les fills fermés IBKR vers le miroir P&L.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--url")
    parser.add_argument("--timeout", type=int, default=25)
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
    since = datetime.fromisoformat(WINDOW_START.replace("Z", "+00:00"))

    if not account:
        print("IBKR_ACCOUNT_ID requis", file=sys.stderr)
        return 2
    # Un compte live commence par U : refus de l'étiqueter practice tant que ce n'est pas dit explicitement.
    environment = os.environ.get("HERMES_IBKR_ENVIRONMENT") or ("practice" if account.startswith("DU") else "")
    if environment not in ("practice", "live"):
        print(f"HERMES_IBKR_ENVIRONMENT requis pour le compte {account} (practice|live)", file=sys.stderr)
        return 2
    if not args.dry_run and (not base or not token):
        print("HERMES_WEB_URL et HERMES_WEB_TOKEN requis", file=sys.stderr)
        return 2

    try:
        data = collect(host, port, client_id, account, since, args.timeout)
    except Exception as exc:                     # noqa: BLE001 - Gateway muet ou mauvais compte
        print(f"Gateway: {exc}", file=sys.stderr)
        if args.dry_run:
            return 1
        degraded = {
            "broker": "ibkr",
            "account_id": account,
            "environment": environment,
            "window_start_utc": WINDOW_START,
            "snapshot": {
                "extraction_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "currency": "CAD",
                "source": SOURCE + " (degraded)",
            },
            "entries": [],
        }
        status, body = post(base + PNL_PATH, token, degraded)
        print(f"état dégradé publié: HTTP {status} {body[:200]}", file=sys.stderr)
        return 1

    fx = FxRates(oanda_key())
    payload, refused = build_payload(account, environment, data["executions"], data["snapshot"], fx)

    if args.dry_run:
        print(json.dumps({"entries": payload["entries"], "refused": refused, "fx_errors": fx.errors},
                         indent=1, default=str))
        return 0

    status, body = post(base + PNL_PATH, token, payload)
    print(json.dumps({"http": status, "response": body[:300], "sent": len(payload["entries"]),
                      "refused": refused, "fx_errors": fx.errors}))
    return 0 if 200 <= status < 300 else 1


if __name__ == "__main__":
    sys.exit(main())
