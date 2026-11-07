#!/usr/bin/env python3
"""Lecture seule : pousse les fills fermés IBKR vers le miroir P&L de hermes-web.

Un seul cycle par exécution (timer systemd horaire). Aucun ordre n'est jamais envoyé.

Le P&L vient de la SOURCE, pas d'un calcul maison. IBKR renseigne `CommissionReport.realizedPNL` sur chaque
exécution qui ferme, déjà net des commissions des deux jambes, et renvoie sa sentinelle (1.797e308) sur une
ouverture. Mesuré sur le compte papier : ouverture = sentinelle, close = -2.47 USD pour 1 MES à -0.25 point,
soit exactement -1.25 de mouvement moins 1.22 de commissions. Conséquence : aucun appariement FIFO, aucun
multiplicateur à deviner, aucune commission à re-soustraire, et aucun historique à reconstituer.

Contrat : POST {HERMES_WEB_URL}/api/trading/pnl-import avec broker=ibkr.
- Identité de ligne : fill_ref = base de l'execId IBKR (idempotent). Une correction arrive avec le dernier segment
  d'execId incrémenté : seule la révision la plus haute est gardée, sinon la quantité et le P&L comptent double.
- Compte : refus de publier si le Gateway n'est pas connecté au compte configuré (managedAccounts), et
  exécutions, positions et résumé sont filtrés sur ce compte. Un compte live (U…) exige
  HERMES_IBKR_ENVIRONMENT=live, sinon le script s'arrête.
- Devise : `pl_native` = realizedPNL dans la devise du rapport, `pl` = CAD au taux {devise}_CAD clôture H1 OANDA
  de l'heure du fill, stocké sur la ligne (exit_reason « IBKR FX 1.403980 »). Bougie absente ou incomplète,
  devise non convertible ⇒ ligne refusée, jamais convertie au hasard.
- `orderRef` nomme la stratégie quand il en porte une, sinon la ligne est un leak.
- Retournement (vendre 2 contre un long de 1) : IBKR ne donne realizedPNL que sur la partie fermée, mais
  `units` reste la taille exécutée ; le P&L reste juste, la quantité affichée est celle du fill.
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
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

SOURCE = "hermes-ibkr-pnl"
PNL_PATH = "/api/trading/pnl-import"
WINDOW_START = "2026-10-01T00:00:00Z"
MONTREAL = ZoneInfo("America/Montreal")
# Fuseau des heures d'exécution IBKR. MESURÉ 2026-11-04 (Gateway Docker 10.50.1f, compte paper DUR967322) :
# `execution.time` sort en UTC sans suffixe (« 20261104 08:06:05 ») et le journal de fills du runtime écrit
# le MÊME instant en Z (08:06:05.56Z, fichier modifié à 03:06 EST). Supposer New York décalait le fill de +5 h,
# donc dans le futur : OANDA refusait la conversion (400 « Time is in the future ») et la ligne était refusée
# à chaque cycle. Surchargeable si un Gateway rend un jour l'heure locale (HERMES_IBKR_EXEC_TZ).
_EXECUTION_TZ: ZoneInfo | None = None


def execution_tz() -> ZoneInfo:
    """Fuseau des heures d'exécution, lu paresseusement (le fichier d'env est chargé après les imports)."""
    global _EXECUTION_TZ
    if _EXECUTION_TZ is None:
        name = os.environ.get("HERMES_IBKR_EXEC_TZ", "UTC")
        try:
            _EXECUTION_TZ = ZoneInfo(name)
        except Exception:                        # noqa: BLE001 - nom inconnu : UTC, la valeur mesurée
            _EXECUTION_TZ = ZoneInfo("UTC")
    return _EXECUTION_TZ

UNAVAILABLE = 1.7976931348623157e308
STATE_PATH = Path.home() / ".local/share/hermes-ibkr/pnl_state.json"
LEDGER_DAYS = 120                # profondeur du registre local
HOME_ENV = Path.home() / ".hermes/.env"
BENIGN_ERRORS = (2104, 2106, 2107, 2158, 2100, 2108, 2119, 2103, 2105, 2157)
FATAL_CODES = (326, 502, 504, 1100, 2110)


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


CONTROL_REFS = {"killswitch", "flatten", "flatten-all", "control"}

UUID_SHAPE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.IGNORECASE)
STRATEGY_SYMBOL_SUFFIX = re.compile(r"_(MGC|MNQ|MES|M2K|MCL|GC|NQ|ES)[A-Z0-9]*$", re.IGNORECASE)

FUTURES_MULTIPLIERS = {
    "MGC": 10.0,
    "MES": 5.0,
    "MNQ": 2.0,
    "M2K": 5.0,
    "MCL": 100.0,
}


def normalize_strategy_ref(ref: str) -> str:
    """Retire le suffixe de symbole ajouté par IbkrOrderDispatcher (ex: sq-mgc-m30-breakout-0-24542_MGC)."""
    return STRATEGY_SYMBOL_SUFFIX.sub("", (ref or "").strip())


def looks_like_strategy(ref: str) -> bool:
    """Un nom de strategie, pas un identifiant technique : un UUID en orderRef creerait une fausse strategie
    par fill (mesure : IbkrBroker passait order.id() la ou le nom de strategie etait attendu)."""
    cleaned = normalize_strategy_ref(ref)
    return bool(cleaned) and not UUID_SHAPE.match(cleaned) and re.match(r"^[A-Za-z][A-Za-z0-9_-]{1,63}$", cleaned) is not None


def is_close(report: dict | None) -> bool:
    """IBKR dit lui-même si une exécution ferme : realizedPNL renseigné, sentinelle sinon."""
    if not report:
        return False
    realized = report.get("realized_pnl")
    return realized is not None and math.isfinite(realized) and abs(realized) < 1e290


def parse_ibkr_time(raw: str) -> datetime:
    """'20261021 05:56:48' (heure Gateway, UTC mesuré) → UTC. Refuse une heure ambiguë (changement d'heure)."""
    cleaned = " ".join(raw.split())
    # Un Gateway recent ajoute parfois le fuseau a l'heure d'execution : on le LIT (le supposer decalerait
    # closed_at, l'heure du taux FX et la date locale sans aucune erreur). Sans suffixe, c'est UTC : mesuré
    # le 2026-11-04 contre le journal de fills du runtime, meme seconde.
    zone = execution_tz()
    match = re.search(r"\s+([A-Za-z][A-Za-z0-9/_+-]*)$", cleaned)
    if match:
        token = match.group(1)
        stripped = cleaned[: match.start()].strip()
        if re.match(r"^\d{4}\d{2}\d{2} \d{2}:\d{2}:\d{2}$", stripped):
            try:
                zone = ZoneInfo("UTC" if token.upper() in ("Z", "UTC") else token)
            except Exception as exc:              # noqa: BLE001 - fuseau inconnu : on refuse la ligne
                raise ValueError(f"fuseau d'execution inconnu ({token})") from exc
            cleaned = stripped
    naive = datetime.strptime(cleaned, "%Y%m%d %H:%M:%S")
    first = naive.replace(tzinfo=zone, fold=0)
    second = naive.replace(tzinfo=zone, fold=1)
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
            "to": min(hour_start + timedelta(hours=2),
                      datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "price": "M",
        })
        request = urllib.request.Request(
            f"https://api-fxpractice.oanda.com/v3/instruments/{pair}/candles?" + query,
            headers={"Authorization": "Bearer " + self.key},
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                candles = json.load(response).get("candles", [])
        except urllib.error.HTTPError as exc:
            # Le corps porte la vraie raison (« Time is in the future », « instrument not tradeable ») : sans
            # lui, un 400 reste opaque et on cherche le bug au mauvais endroit.
            try:
                detail = exc.read().decode("utf-8", "replace")[:160]
            except Exception:                    # noqa: BLE001
                detail = ""
            self.errors.append(f"{pair} {hour_start:%Y-%m-%dT%H}: HTTP {exc.code} {detail}")
            return None
        except Exception as exc:                     # noqa: BLE001 - réseau : la ligne sera refusée
            self.errors.append(f"{pair} {hour_start:%Y-%m-%dT%H}: {exc}")
            return None
        candle = candle_for(candles, hour_start)
        if candle is None:
            # Le fill tombe dans une heure encore ouverte : sa bougie n'existe pas encore. On se rabat
            # sur la derniere cloture H1 COMPLETE anterieure, deja presente dans la fenetre demandee,
            # au lieu de refuser un taux qui existe. Un vrai trou de cotation reste un refus.
            earlier = sorted(
                (c for c in candles
                 if c.get("complete") is True
                 and str(c.get("time", ""))[:19] < hour_start.strftime("%Y-%m-%dT%H:%M:%S")),
                key=lambda c: str(c.get("time", ""))[:19],
            )
            candle = earlier[-1] if earlier else None
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
        report = execution.get("report")
        if report is None:
            refused.append(f"{execution['exec_id']}: aucun rapport de commission reçu, impossible de savoir "
                           f"si ce fill ferme une position")
            continue
        if not is_close(report):
            continue                                  # ouverture : le miroir enregistre de l'argent
        try:
            closed_at = parse_ibkr_time(execution["time"])
        except (ValueError, TypeError) as exc:
            refused.append(f"{execution['exec_id']}: {exc}")
            continue
        # Un fill ne peut pas avoir eu lieu dans le futur. Si l'heure du Gateway est relue avec le mauvais
        # fuseau, c'est exactement ce qui arrive : on le dit ici, pas dans un 400 d'OANDA illisible.
        if closed_at > datetime.now(timezone.utc) + timedelta(seconds=120):
            refused.append(f"{execution['exec_id']}: heure d'exécution dans le futur "
                           f"({closed_at:%Y-%m-%dT%H:%M:%SZ}) : fuseau du Gateway à vérifier "
                           f"(HERMES_IBKR_EXEC_TZ={execution_tz().key})")
            continue

        native = float(report["realized_pnl"])       # déjà net des commissions des deux jambes
        # IBKR ne dit pas explicitement dans quelle devise realizedPNL est exprime ; quand la devise du
        # contrat et celle du rapport different, on refuse plutot que de choisir au hasard.
        contract_ccy = (execution.get("currency") or "").upper()
        report_ccy = (report.get("currency") or "").upper()
        if contract_ccy and report_ccy and contract_ccy != report_ccy:
            refused.append(f"{execution['exec_id']}: devise du contrat ({contract_ccy}) != devise du rapport "
                           f"({report_ccy}), P&L non attribuable")
            continue
        currency = contract_ccy or report_ccy
        if not currency:
            refused.append(f"{execution['exec_id']}: devise du rapport inconnue")
            continue

        rate = 1.0
        if currency != "CAD":                        # pl est en CAD, point : on compare a CAD, pas au compte
            fetched = fx.rate(currency, closed_at)
            if fetched is None:
                refused.append(f"{execution['exec_id']}: taux {currency}→{account_currency} introuvable, "
                               f"ligne refusée")
                continue
            rate = fetched

        order_ref = (execution.get("order_ref") or "").strip()
        normalized_ref = normalize_strategy_ref(order_ref)
        tag = order_ref or f"IBKR-ORDER-{execution['order_id']}"
        # Trois natures, jamais confondues : une strategie, un ordre de controle (kill-switch, flatten
        # declenche a la main), ou un fill non attribue.
        ref_lower = normalized_ref.lower()
        if ref_lower in CONTROL_REFS:
            kind, strategy_name = "control", None
        elif looks_like_strategy(normalized_ref):
            kind, strategy_name = "strategy", normalized_ref
        else:
            kind, strategy_name = "leak", None
        local = closed_at.astimezone(MONTREAL)

        entries.append({
            # Base de l'execId : une correction arrivee plus tard ecrase la ligne au lieu de la doubler.
            "fill_ref": base_exec_id(execution["exec_id"])[0],
            "kind": kind,
            "strategy": strategy_name,
            "tag": tag,
            "instrument": execution["symbol"] or str(execution["con_id"]),
            # execution["side"] est le sens de l'ordre de SORTIE (SLD ferme un LONG/BUY, BOT couvre un SHORT/SELL).
            # Le contrat de trading_pnl_entries demande la direction de la position ouverte (BUY = Long, SELL = Short).
            "direction": execution.get("entry_side") or ("SELL" if execution["side"] == "BOT" else "BUY"),
            "units": execution["shares"],
            # Le prix d'entrée n'est pas connu de l'exécution de sortie Gateway : on ne l'invente pas.
            # S'il provient du journal local (round-trip complet), il est transmis tel quel.
            "entry_price": execution.get("entry_price"),
            "exit_price": execution["price"],
            "pl": round(native * rate, 4),
            "pl_native": round(native, 4),
            "currency": currency,
            # La commission est dans la devise du rapport : si elle differe de celle du contrat, on ne
            # l'etiquette pas de travers, on ne la publie pas.
            "commission": (None if report.get("commission") is None else round(float(report["commission"]), 4)
                           if (report.get("currency") or "").upper() in ("", currency) else None),
            "financing": 0.0,
            # exit_reason est borné à 32 caractères par l'API : le taux garde sa place, le bavardage non.
            "exit_reason": (f"IBKR FX {rate:.6f}" if rate != 1.0 else "IBKR"),
            "order_id": str(execution["order_id"]),
            "opened_at_utc": execution.get("opened_at_utc"),
            "closed_at_utc": closed_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "local_date": local.strftime("%Y-%m-%d"),
            "iso_week": "%s-W%02d" % local.isocalendar()[:2],
            "iso_month": local.strftime("%Y-%m"),
            "iso_year": local.strftime("%Y"),
            "dow": local.strftime("%a"),
            "hour_local": local.hour,
        })

    # Aucune fenetre annoncee : le Gateway ne rend qu'une fenetre courte d'executions, donc la lane ne
    # pretend rien couvrir. Une fenetre trop large ferait croire a une couverture qui n'existe pas.
    payload = {
        "broker": "ibkr",
        "account_id": account_id,
        "environment": environment,
        "window_start_utc": None,
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
            self.currencies: dict[str, str] = {}
            self.managed: list[str] = []
            self.positions = 0
            self.errors: list[str] = []

        def nextValidId(self, orderId: int) -> None:      # noqa: N802 - API ibapi
            self.ready.set()

        def error(self, reqId, code, msg, advancedOrderRejectJson=""):  # noqa: N802
            # Un avertissement non sollicite (2103, 2105, 2157…) ne doit pas condamner le cycle : on ne
            # retient que les erreurs des requetes qu'on a faites et les vraies pertes de connexion.
            if code in BENIGN_ERRORS:
                return
            if reqId in (1, 2) or code in FATAL_CODES:
                self.errors.append(f"{code}: {msg}")

        def managedAccounts(self, accountsList: str) -> None:  # noqa: N802
            self.managed = [a for a in (accountsList or "").split(",") if a]
            self.accounts_known.set()

        def execDetails(self, reqId, contract, execution):  # noqa: N802
            if execution.acctNumber != account:
                return                                    # compte vide ou different : jamais publie
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
            if currency:                             # devise de la valeur, pas un tag « Currency »
                self.currencies[tag] = currency

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
        if not client.accounts_known.wait(min(5, timeout)):
            raise TimeoutError("managedAccounts non reçu : impossible de vérifier le compte")
        if account not in client.managed:
            raise ConnectionError(
                f"le Gateway n'est pas connecté au compte {account} (comptes vus : {client.managed or 'aucun'})"
            )

        client.reqAccountSummary(1, "All", "NetLiquidation,TotalCashValue,UnrealizedPnL,RealizedPnL,Currency")
        client.reqPositions()
        flt = ExecutionFilter()
        flt.acctCode = account
        flt.time = since.astimezone(execution_tz()).strftime("%Y%m%d-%H:%M:%S")
        client.reqExecutions(2, flt)

        for event, label in ((client.exec_done, "exécutions"), (client.summary_done, "résumé de compte"),
                             (client.pos_done, "positions")):
            if not event.wait(timeout):
                raise TimeoutError(f"{label} non reçues dans le délai")
        # Les commissionReport peuvent suivre execDetailsEnd : on leur laisse le temps d'arriver, sinon un
        # close passerait pour une ouverture et disparaitrait en silence.
        deadline = time.monotonic() + min(10, timeout)
        while time.monotonic() < deadline:
            if {e["exec_id"] for e in client.executions} <= set(client.reports):
                break
            time.sleep(0.2)
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
        try:
            value = float(raw)
        except (TypeError, ValueError):
            return None
        return None if abs(value) > 1e290 else value

    snapshot = {
        "nav": num("NetLiquidation"),
        "balance": num("TotalCashValue"),
        "unrealized_pl": num("UnrealizedPnL"),
        "open_trade_count": client.positions,
        # Devise de la NAV telle que le Gateway la rend ; inconnue = on refuse plus loin plutot que d'inventer.
        "currency": client.currencies.get("NetLiquidation") or "",
        "extraction_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": SOURCE,
    }
    return {"executions": client.executions, "snapshot": snapshot}


# --------------------------------------------------------------------------- registre local


def load_state() -> dict:
    """Registre local : derniere reussite + executions deja vues, pour ne rien perdre entre deux cycles."""
    if not STATE_PATH.exists():
        return {"last_success_utc": None, "executions": {}}
    try:
        state = json.loads(STATE_PATH.read_text())
    except (json.JSONDecodeError, OSError) as exc:
        alert(f"registre local illisible ({exc}) : il repart a vide, les fills deja publies restent dans le hub")
        return {"last_success_utc": None, "executions": {}}
    state.setdefault("executions", {})
    state.setdefault("last_success_utc", None)
    state.setdefault("alerts", {})
    return state


def save_state(state: dict) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    cutoff = (datetime.now(timezone.utc) - timedelta(days=LEDGER_DAYS)).strftime("%Y-%m-%dT%H:%M:%SZ")
    kept = {}
    for exec_id, execution in state["executions"].items():
        try:
            if parse_ibkr_time(execution["time"]).strftime("%Y-%m-%dT%H:%M:%SZ") >= cutoff:
                kept[exec_id] = execution
        except (ValueError, KeyError, TypeError):
            kept[exec_id] = execution              # illisible : on le garde, il sera refuse explicitement
    state["executions"] = kept
    temporary = STATE_PATH.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=1, default=str))
    temporary.replace(STATE_PATH)


def backfill_since(state: dict, fallback_days: int = 7) -> datetime:
    """Depuis quand demander les executions : la derniere reussite, sinon une fenetre de repli."""
    last = state.get("last_success_utc")
    if last:
        try:
            return datetime.fromisoformat(last.replace("Z", "+00:00"))
        except ValueError:
            pass
    return datetime.now(timezone.utc) - timedelta(days=fallback_days)


def load_jsonl_closes(fills_dir: Path, existing_executions: dict[str, dict]
                      ) -> tuple[list[dict], list[tuple[str, str, str]]]:
    """Reconstruit les round-trips fermés depuis data/fills/*-paper.jsonl lorsque le Gateway
    a redémarré avant le passage du publisher P&L.

    Retourne (lignes synthétisées, jumeaux à retirer). Un round-trip du journal qui a une exécution
    Gateway native à ±20 s ne doit pas être publié deux fois : la native porte le P&L du venue, le jumeau
    est retiré du miroir (l'identité d'une ligne est son fill_ref, donc ils ne se dédoublonnent pas seuls).
    Chaque jumeau est (exec_id local, fill_ref du miroir, exec_id natif) : l'exec_id porte la révision,
    le fill_ref est sa base, et les deux ne sont pas interchangeables."""
    if not fills_dir.is_dir():
        return [], []

    existing_times: list[tuple[str, datetime, str, str]] = []
    for ex_id, ex in existing_executions.items():
        if str(ex_id).startswith("jsonl."):
            continue
        try:
            ex_strat = normalize_strategy_ref(str(ex.get("order_ref", "")))
            existing_times.append((str(ex.get("symbol", "")), parse_ibkr_time(ex["time"]), str(ex_id), ex_strat))
        except Exception:  # noqa: BLE001
            continue

    synthesized: list[dict] = []
    retired: list[tuple[str, str, str]] = []
    for path in sorted(fills_dir.glob("*-paper.jsonl")):
        open_leg: dict | None = None
        for idx, raw_line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
            raw_line = raw_line.strip()
            if not raw_line:
                continue
            try:
                rec = json.loads(raw_line)
            except json.JSONDecodeError:
                continue
            side = str(rec.get("side", "")).upper()
            symbol = str(rec.get("symbol", "")).upper()
            strategy = normalize_strategy_ref(str(rec.get("strategy", "")))
            if side not in ("BUY", "SELL") or not symbol:
                continue

            if open_leg is None or open_leg.get("side") == side:
                open_leg = rec
                continue

            # Fermeture d'un round-trip (jambe opposée)
            try:
                entry_price = float(open_leg["filledPrice"])
                exit_price = float(rec["filledPrice"])
                qty = min(float(open_leg.get("quantity", 1.0)), float(rec.get("quantity", 1.0)))
                open_comm = float(open_leg.get("commission", 0.62))
                close_comm = float(rec.get("commission", 0.62))
                open_dt = datetime.fromisoformat(str(open_leg["timestamp"]).replace("Z", "+00:00")).astimezone(timezone.utc)
                close_dt = datetime.fromisoformat(str(rec["timestamp"]).replace("Z", "+00:00")).astimezone(timezone.utc)
            except (KeyError, TypeError, ValueError):
                open_leg = None
                continue

            entry_side = "BUY" if side == "SELL" else "SELL"
            open_leg = None
            mult = FUTURES_MULTIPLIERS.get(symbol)
            if mult is None:
                continue

            # Ignorer si une exécution Gateway native existe déjà à ±20s sur ce symbole : c'est la même
            # transaction, et le jumeau du journal doit sortir du miroir au lieu d'y rester en double.
            # Le ref à retirer est celui que le miroir stocke réellement : la BASE de l'execId, sans le
            # suffixe de révision (fill_ref = base_exec_id(...)), sinon la suppression ne trouve rien.
            twin_exec_id = f"jsonl.{symbol}.{close_dt.strftime('%Y%m%dT%H%M%SZ')}.01"
            twin_ref = base_exec_id(twin_exec_id)[0]
            native = next(
                ((dt, ex_id) for sym, dt, ex_id, ex_strat in existing_times
                 if sym and (sym.startswith(symbol) or symbol.startswith(sym))
                 and (not ex_strat or not strategy or ex_strat == strategy)
                 and abs((dt - close_dt).total_seconds()) <= 20),
                None,
            )
            if native is not None:
                native_exec = existing_executions.get(native[1])
                if isinstance(native_exec, dict):
                    native_exec["entry_price"] = entry_price
                    native_exec["opened_at_utc"] = open_dt.strftime("%Y-%m-%dT%H:%M:%SZ")
                    native_exec["entry_side"] = entry_side
                retired.append((twin_exec_id, twin_ref, native[1]))
                continue

            pts = (exit_price - entry_price) if side == "SELL" else (entry_price - exit_price)
            realized_usd = round(pts * mult * qty - (open_comm + close_comm), 4)
            stamp = close_dt.strftime("%Y%m%dT%H%M%SZ")
            exec_id = f"jsonl.{symbol}.{stamp}.01"

            synthesized.append({
                "exec_id": exec_id,
                "time": close_dt.strftime("%Y%m%d %H:%M:%S UTC"),
                "side": "BOT" if side == "BUY" else "SLD",
                "entry_side": entry_side,
                "shares": qty,
                "price": exit_price,
                "entry_price": entry_price,
                "opened_at_utc": open_dt.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "order_id": 90000 + idx,
                "order_ref": f"{strategy}_{symbol}" if strategy else "",
                "currency": "USD",
                "con_id": 0,
                "symbol": symbol,
                "report": {
                    "commission": round(open_comm + close_comm, 4),
                    "currency": "USD",
                    "realized_pnl": realized_usd,
                },
            })
    return synthesized, retired


# --------------------------------------------------------------------------- alerte

ALERT_REPEAT_HOURS = 12      # une meme ligne refusee ne doit pas reveiller Martin a chaque cycle de 5 min
ALERT_RETENTION_DAYS = 7


def should_alert(state: dict, key: str) -> bool:
    """Vrai si cette alerte n'a pas deja ete envoyee pour la meme cause dans la fenetre de repetition."""
    sent = state.setdefault("alerts", {})
    now = datetime.now(timezone.utc)
    for existing, stamp in list(sent.items()):
        try:
            if now - datetime.fromisoformat(str(stamp).replace("Z", "+00:00")) > timedelta(days=ALERT_RETENTION_DAYS):
                del sent[existing]
        except ValueError:
            del sent[existing]
    last = sent.get(key)
    if last:
        try:
            if now - datetime.fromisoformat(str(last).replace("Z", "+00:00")) < timedelta(hours=ALERT_REPEAT_HOURS):
                return False
        except ValueError:
            pass
    sent[key] = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    return True


def telegram_token_and_chat() -> tuple[str | None, str | None]:
    """Token et canal lus dans ~/.hermes/.env sans charger tout le fichier dans l'environnement."""
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("TELEGRAM_HOME_CHANNEL")
    if (not token or not chat) and HOME_ENV.exists():
        try:
            lines = HOME_ENV.read_text().splitlines()
        except OSError:
            lines = []
        for line in lines:
            key, _, value = line.strip().partition("=")
            value = value.split("#")[0].strip().strip('"').strip("'")
            if key == "TELEGRAM_BOT_TOKEN" and not token:
                token = value
            elif key == "TELEGRAM_HOME_CHANNEL" and not chat:
                chat = value
    return token, chat


def alert(text: str) -> None:
    """Une alerte qui ne casse jamais le cycle : si Telegram ne repond pas, on l'ecrit et on continue."""
    token, chat = telegram_token_and_chat()
    print(f"ALERTE: {text}", file=sys.stderr)
    if not token or not chat:
        print("  (pas de token/canal Telegram : alerte seulement dans le journal)", file=sys.stderr)
        return
    payload = urllib.parse.urlencode({"chat_id": chat, "text": f"IBKR P&L: {text}",
                                      "disable_web_page_preview": "true"}).encode()
    try:
        with urllib.request.urlopen(
            f"https://api.telegram.org/bot{token}/sendMessage", data=payload, timeout=15
        ) as response:
            response.read()
    except Exception as exc:                        # noqa: BLE001 - une alerte ratee ne doit pas casser le run
        print(f"  (Telegram injoignable: {exc})", file=sys.stderr)


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
    except (urllib.error.URLError, OSError) as exc:
        # Hub injoignable : un code 0 plutot qu'une trace, pour que l'appelant alerte au lieu de planter.
        return 0, str(exc)


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
    # Le Gateway rend l'heure d'execution en UTC, sans suffixe (mesure 2026-11-04 : 08:06:05 cote Gateway
    # = 08:06:05Z cote journal du runtime). La lire comme New York la mettait 5 h dans le futur.
    checks["heure Gateway sans fuseau lue comme UTC"] = (
        parse_ibkr_time("20261021  05:56:50").strftime("%Y-%m-%dT%H:%M:%SZ") == "2026-10-21T05:56:50Z"
    )

    execution = {
        "exec_id": "a.b.01.01", "time": "20261021 05:56:50", "side": "SLD", "shares": 1.0, "price": 7766.25,
        "order_id": 1001, "order_ref": "", "symbol": "MESZ6", "con_id": 1,
        "report": {"realized_pnl": -2.47, "currency": "CAD", "commission": 0.61},
    }
    payload, refused = build_payload("DU123", "practice", [execution], {"currency": "CAD"}, FxRates(None))
    entry = payload["entries"][0]
    checks["pl natif = realizedPNL (net des commissions)"] = entry["pl_native"] == -2.47
    checks["sans ordre nommé : leak"] = entry["kind"] == "leak"
    # Un UUID en orderRef ne doit jamais devenir un nom de strategie.
    uuid_ref = dict(execution, order_ref="3f2b8c1e-9a4d-4b7c-8e15-2d6f0a9c4b71")
    uuid_entry = build_payload("DU123", "practice", [uuid_ref], {"currency": "CAD"}, FxRates(None))[0]["entries"][0]
    checks["orderRef en UUID : leak, pas strategie"] = uuid_entry["kind"] == "leak"
    named = dict(execution, order_ref="mesbreakout")
    named_entry = build_payload("DU123", "practice", [named], {"currency": "CAD"}, FxRates(None))[0]["entries"][0]
    checks["orderRef nomme : strategie"] = named_entry["kind"] == "strategy" and named_entry["strategy"] == "mesbreakout"
    control = dict(execution, order_ref="killswitch")
    control_entry = build_payload("DU123", "practice", [control], {"currency": "CAD"}, FxRates(None))[0]["entries"][0]
    checks["kill-switch : controle, ni strategie ni leak"] = (
        control_entry["kind"] == "control" and control_entry["strategy"] is None
    )
    checks["units = taille exécutée"] = entry["units"] == 1.0
    checks["devise CAD : pas de conversion"] = entry["pl"] == -2.47
    checks["prix d'entrée non inventé"] = entry["entry_price"] is None
    checks["fill_ref = base d'execId"] = entry["fill_ref"] == "a.b.01"

    # Une correction vue par une AUTRE exécution doit viser la même ligne du miroir.
    later, _ = build_payload("DU123", "practice",
                             [dict(execution, exec_id="a.b.01.07")], {"currency": "CAD"}, FxRates(None))
    checks["correction tardive : même fill_ref"] = later["entries"][0]["fill_ref"] == entry["fill_ref"]

    # Devise étrangère : pl est en CAD, donc conversion demandée ; sans taux, refus.
    foreign = dict(execution, report={"realized_pnl": -2.0, "currency": "EUR", "commission": 0.5})
    refused_payload, refused_fx = build_payload("DU123", "practice", [foreign], {"currency": "CAD"},
                                                FxRates(None))
    checks["devise étrangère : refus sans taux"] = (
        refused_payload["entries"] == [] and len(refused_fx) == 1
    )

    class FixedFx(FxRates):
        def rate(self, currency, instant):
            return 1.5

    converted, _ = build_payload("DU123", "practice", [foreign], {"currency": "CAD"}, FixedFx(None))
    checks["conversion appliquée (EUR→CAD)"] = converted["entries"][0]["pl"] == -3.0
    checks["taux conservé sur la ligne"] = converted["entries"][0]["exit_reason"] == "IBKR FX 1.500000"

    missing, refused_missing = build_payload("DU123", "practice", [dict(execution, report=None)],
                                             {"currency": "CAD"}, FxRates(None))
    checks["close sans rapport : refusé, pas ignoré en silence"] = (
        missing["entries"] == [] and len(refused_missing) == 1
    )

    # Fuseau : lu quand il est fourni, refuse quand il est inconnu.
    checks["fuseau explicite lu (UTC)"] = (
        parse_ibkr_time("20261021 09:56:50 UTC").strftime("%H:%M") == "09:56"
    )
    try:
        parse_ibkr_time("20261021 05:56:50 Mars/Olympus")
        checks["fuseau inconnu refuse"] = False
    except ValueError:
        checks["fuseau inconnu refuse"] = True
    # Devise contrat != devise rapport : P&L non attribuable.
    mismatch = dict(execution, currency="EUR",
                    report={"realized_pnl": -2.0, "currency": "USD", "commission": 0.5})
    mm_payload, mm_refused = build_payload("DU123", "practice", [mismatch], {"currency": "CAD"}, FxRates(None))
    checks["devises divergentes refusees"] = mm_payload["entries"] == [] and len(mm_refused) == 1
    # Commission inconnue : null, jamais 0.0.
    nofee = dict(execution, report={"realized_pnl": -2.47, "currency": "CAD", "commission": None})
    nofee_entry = build_payload("DU123", "practice", [nofee], {"currency": "CAD"}, FxRates(None))[0]["entries"][0]
    checks["commission inconnue = null"] = nofee_entry["commission"] is None
    # Aucune couverture revendiquee.
    checks["aucune fenetre revendiquee"] = (
        build_payload("DU123", "practice", [execution], {"currency": "CAD"}, FxRates(None))[0]["window_start_utc"]
        is None
    )

    # Un fill ne peut pas avoir eu lieu dans le futur : si le fuseau est mal lu, on le dit ici plutot que de
    # laisser OANDA repondre « Time is in the future » sans explication.
    future = dict(execution, exec_id="a.b.99.01", time="20990101 00:00:00")
    fut_payload, fut_refused = build_payload("DU123", "practice", [future], {"currency": "CAD"}, FxRates(None))
    checks["heure dans le futur refusee et nommee"] = (
        fut_payload["entries"] == [] and any("futur" in reason for reason in fut_refused)
    )

    # Journal + execution native a ±20 s = la meme transaction : le jumeau du journal doit sortir du miroir,
    # sinon le hub compte deux fois le meme trade (les fill_ref differents ne se dedoublonnent pas).
    with tempfile.TemporaryDirectory() as tmp:
        fills = Path(tmp)
        (fills / "strat-MGC-paper.jsonl").write_text("\n".join([
            json.dumps({"strategy": "strat", "symbol": "MGC", "side": "BUY", "quantity": 1.0,
                        "filledPrice": 4008.0, "commission": 0.62, "timestamp": "2026-11-04T06:00:11Z"}),
            json.dumps({"strategy": "strat", "symbol": "MGC", "side": "SELL", "quantity": 1.0,
                        "filledPrice": 4041.1, "commission": 0.62, "timestamp": "2026-11-04T08:06:05Z"}),
        ]) + "\n")
        native = {"0000e1a7.6aeb3cc6.01.01": {
            "exec_id": "0000e1a7.6aeb3cc6.01.01", "time": "20261104 08:06:05", "symbol": "MGCZ6"}}
        synth, retired = load_jsonl_closes(fills, native)
        lonely, lonely_retired = load_jsonl_closes(fills, {})
        # Le ref retire doit etre EXACTEMENT celui que le miroir stockerait pour ce jumeau (la base de
        # l'execId, sans suffixe de revision) : un ".01" en trop et la suppression ne trouve aucune ligne.
        lonely_payload, _ = build_payload("DU123", "practice", lonely, {"currency": "CAD"}, FixedFx(None))
        twin_ref = lonely_payload["entries"][0]["fill_ref"]
        checks["jumeau du journal retire quand la native existe"] = (
            synth == [] and retired == [("jsonl.MGC.20261104T080605Z.01", twin_ref, "0000e1a7.6aeb3cc6.01.01")]
        )
        checks["native enrichie avec entry_price et opened_at_utc du journal"] = (
            native["0000e1a7.6aeb3cc6.01.01"].get("entry_price") == 4008.0
            and native["0000e1a7.6aeb3cc6.01.01"].get("opened_at_utc") == "2026-11-04T06:00:11Z"
            and native["0000e1a7.6aeb3cc6.01.01"].get("entry_side") == "BUY"
        )
        checks["round-trip du journal reconstruit sans native"] = (
            len(lonely) == 1 and lonely[0]["exec_id"] == "jsonl.MGC.20261104T080605Z.01" and lonely_retired == []
        )

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
    # Un compte live commence par U, un compte papier par DU : tout ecart est refuse, dans les deux sens.
    expected = "practice" if account.startswith("D") else "live"
    environment = os.environ.get("HERMES_IBKR_ENVIRONMENT") or expected
    if environment != expected:
        print(f"HERMES_IBKR_ENVIRONMENT={environment} ne correspond pas au compte {account} ({expected})",
              file=sys.stderr)
        return 2
    if not args.dry_run and (not base or not token):
        print("HERMES_WEB_URL et HERMES_WEB_TOKEN requis", file=sys.stderr)
        return 2

    state = load_state()
    since = backfill_since(state)
    # Une execution sans rapport de commission doit etre redemandee : sinon son close est refuse a vie.
    for execution in state["executions"].values():
        if execution.get("report") is None:
            try:
                oldest = parse_ibkr_time(execution["time"])
            except (ValueError, KeyError, TypeError):
                continue
            since = min(since, oldest)

    cycle_started = datetime.now(timezone.utc)
    try:
        data = collect(host, port, client_id, account, since, args.timeout)
    except Exception as exc:                     # noqa: BLE001 - Gateway muet ou mauvais compte
        print(f"Gateway: {exc}", file=sys.stderr)
        if should_alert(state, "gateway:" + str(exc)[:80]):
            alert(f"cycle echoue, Gateway: {exc}")
            save_state(state)
        if args.dry_run:
            return 1
        degraded = {
            "broker": "ibkr",
            "account_id": account,
            "environment": environment,
            "window_start_utc": None,
            "snapshot": {
                "extraction_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "currency": None,
                "source": SOURCE + " (degraded)",
            },
            "entries": [],
        }
        status, body = post(base + PNL_PATH, token, degraded)
        print(f"état dégradé publié: HTTP {status} {body[:200]}", file=sys.stderr)
        return 1

    fx = FxRates(oanda_key())
    if not data["snapshot"].get("currency"):
        print("devise du compte inconnue (accountSummary sans devise) : rien n'est publie", file=sys.stderr)
        alert("cycle sans devise de compte : rien n'est publie")
        return 1

    # Fusion registre + nouvelles executions : un fill vu par un cycle precedent mais jamais publie
    # (echec, refus, coupure) repart dans le payload au lieu d'etre perdu.
    for execution in data["executions"]:
        stored = state["executions"].get(execution["exec_id"])
        # Une ligne deja vue AVEC son rapport ne doit jamais etre remplacee par la meme sans rapport : le
        # fill resterait refuse a chaque cycle et son P&L ne serait plus jamais corrige.
        if stored is not None and stored.get("report") is not None and execution.get("report") is None:
            continue
        state["executions"][execution["exec_id"]] = execution

    env_fills = os.environ.get("TB_FUTURES_FILLS_DIR", "").strip()
    if env_fills:
        fills_dir = Path(env_fills)
    else:
        repo_fills = Path(__file__).resolve().parent.parent / "data" / "fills"
        fills_dir = repo_fills if repo_fills.is_dir() else (Path.home() / "projects/trading-bridge/data/fills")
    synthesized, retire_candidates = load_jsonl_closes(fills_dir, state["executions"])
    for jsonl_exec in synthesized:
        state["executions"].setdefault(jsonl_exec["exec_id"], jsonl_exec)

    save_state(state)                  # vu = conserve, meme si l'envoi echoue ensuite
    # Un jumeau du journal dont la transaction a une execution native ne part JAMAIS dans le payload : sinon
    # la meme transaction est publiee deux fois (fill_ref differents, le hub ne peut pas les rapprocher).
    twins = {twin_exec_id for twin_exec_id, _ref, _native_id in retire_candidates}
    merged = [execution for exec_id, execution in state["executions"].items() if exec_id not in twins]
    payload, refused = build_payload(account, environment, merged, data["snapshot"], fx)

    # Un jumeau du journal ne sort du miroir que si la ligne native qui le remplace est REELLEMENT publiee :
    # sinon un refus (taux FX manquant) retirerait la transaction du registre au lieu de la garder.
    published = {entry["fill_ref"] for entry in payload["entries"]}
    retired = sorted({ref for _exec_id, ref, native_id in retire_candidates
                      if base_exec_id(native_id)[0] in published and ref not in published})
    if retired:
        payload["retired_fill_refs"] = retired

    if args.dry_run:
        print(json.dumps({"entries": payload["entries"], "refused": refused, "fx_errors": fx.errors,
                          "retired_fill_refs": payload.get("retired_fill_refs", []),
                          "window_start_utc": payload["window_start_utc"],
                          "since": since.strftime("%Y-%m-%dT%H:%M:%SZ"),
                          "ledger": len(state["executions"])}, indent=1, default=str))
        return 1 if (refused or fx.errors) else 0

    status, body = post(base + PNL_PATH, token, payload)
    print(json.dumps({"http": status, "response": body[:300], "sent": len(payload["entries"]),
                      "refused": refused, "fx_errors": fx.errors}))
    if not 200 <= status < 300:
        excerpt = re.sub(r"(?i)(bearer|token|authorization)[^\s,;\"]*", "[masque]", body[:160])
        alert(f"le hub a refuse le payload (HTTP {status}) : {excerpt}")
        return 1

    # Registre ecrit seulement apres un envoi accepte : sinon le prochain cycle rejouerait les memes fills.
    # Debut de la collecte : sinon un fill arrive entre la requete et le POST resterait hors de la prochaine.
    state["last_success_utc"] = cycle_started.strftime("%Y-%m-%dT%H:%M:%SZ")
    save_state(state)

    if refused or fx.errors:
        # Un fill refuse n'est pas un detail : sans alerte, il disparaitrait en silence. Mais la meme ligne
        # refusee a chaque cycle de 5 minutes noierait le canal : une seule alerte par cause et par 12 h.
        fresh = [r for r in refused if should_alert(state, "refused:" + r.split(":")[0])]
        fresh_fx = [e for e in fx.errors if should_alert(state, "fx:" + e)]
        save_state(state)
        if fresh or fresh_fx:
            alert("fills non publies: " + "; ".join((fresh + fresh_fx)[:3]))
        else:
            print(f"{len(refused)} refus + {len(fx.errors)} erreurs FX deja signales : pas de nouvelle alerte",
                  file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
