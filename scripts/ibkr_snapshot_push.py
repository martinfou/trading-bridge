#!/usr/bin/env python3
"""Lecture seule : pousse l'état du compte IBKR (compte + positions) vers hermes-web.

Un seul cycle par exécution (timer systemd toutes les 5 min). Aucun ordre n'est jamais envoyé.

Contrat : POST {HERMES_WEB_URL}/api/trading/broker/snapshot
Le payload est celui attendu par IbkrPanel.vue (account / connection / positions / open_orders).
Aucune métrique absente n'est remplacée par 0 : la clé est omise et le panel affiche « — ».

Usage:
    ibkr_snapshot_push.py [--dry-run] [--selftest] [--url URL] [--timeout S]

Variables d'environnement (ou fichier HERMES_IBKR_ENV, défaut ~/.config/hermes-ibkr/publisher.env):
    IBKR_GATEWAY_HOST (127.0.0.1)   IBKR_GATEWAY_PORT (4002)   IBKR_ACCOUNT_ID (requis)
    HERMES_IBKR_CLIENT_ID (90)      HERMES_WEB_URL (requis)    HERMES_WEB_TOKEN (requis)
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SOURCE = "hermes-ibkr-publisher"
SNAPSHOT_PATH = "/api/trading/broker/snapshot"

TAGS = (
    "NetLiquidation,TotalCashValue,BuyingPower,AvailableFunds,"
    "InitMarginReq,MaintMarginReq,GrossPositionValue,UnrealizedPnL,RealizedPnL,Currency"
)

# Clé du payload -> tag IBKR. Un tag absent est OMIS du payload, jamais remplacé par 0.
ACCOUNT_TAGS = {
    "net_liquidation": "NetLiquidation",
    "total_cash": "TotalCashValue",
    "buying_power": "BuyingPower",
    "available_funds": "AvailableFunds",
    "init_margin": "InitMarginReq",
    "maint_margin": "MaintMarginReq",
    "gross_position_value": "GrossPositionValue",
    "unrealized_pl": "UnrealizedPnL",
    "realized_pl": "RealizedPnL",
}

# IBKR renvoie Double.MAX_VALUE quand une métrique est indisponible : ce n'est pas une valeur.
UNAVAILABLE = 1.7976931348623157e308

# Codes qui signifient réellement « la session n'est pas saine ». Les autres (2104/2106/2158,
# ou un avertissement de séance) ne doivent pas faire annoncer « déconnecté ».
FATAL_CODES = {326, 502, 504, 1100, 1102, 2110}


def load_env_file(path: Path) -> None:
    """Charge un fichier KEY=VALUE sans écraser ce qui est déjà dans l'environnement."""
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def require_complete(summary_done: bool, positions_done: bool, fatal_error: str | None) -> None:
    """Exige un état complet ET sain. Une seule des deux réponses ne prouve rien.

    Fonction pure, hors ibapi, pour que le selftest puisse l'exercer sans Gateway.
    """
    if fatal_error:
        raise ConnectionError(f"erreur fatale du Gateway : {fatal_error}")
    if not summary_done or not positions_done:
        missing = []
        if not summary_done:
            missing.append("accountSummary")
        if not positions_done:
            missing.append("positions")
        raise TimeoutError(f"flux incomplet dans le délai : {', '.join(missing)}")


class Probe:
    """Collecte account summary + positions, avec attente bornée et déconnexion garantie."""

    def __init__(self) -> None:
        from ibapi.client import EClient
        from ibapi.wrapper import EWrapper

        class _Client(EWrapper, EClient):  # type: ignore[misc, valid-type]
            def __init__(self) -> None:
                EClient.__init__(self, self)
                self.summary: dict[str, float] = {}
                self.currency = ""
                self.positions: list[dict] = []
                self.account = ""
                self.summary_done = threading.Event()
                self.positions_done = threading.Event()
                self.connected_flag = threading.Event()
                self.fatal_error: str | None = None
                self.errors: list[str] = []

            def nextValidId(self, orderId: int) -> None:
                self.connected_flag.set()

            def accountSummary(self, reqId, account, tag, value, currency):
                self.account = account
                if tag == "Currency":
                    self.currency = self.currency or value
                    return
                try:
                    parsed = float(value)
                except (TypeError, ValueError):
                    return
                if parsed != UNAVAILABLE:
                    self.summary[tag] = parsed

            def accountSummaryEnd(self, reqId):
                self.summary_done.set()

            def position(self, account, contract, position, avgCost):
                if not position:
                    return
                self.account = account
                self.positions.append(
                    {
                        "symbol": contract.symbol,
                        "side": "LONG" if position > 0 else "SHORT",
                        "quantity": abs(float(position)),
                        "average_price": float(avgCost),
                        "sec_type": contract.secType,
                        "currency": contract.currency or "",
                        "local_symbol": contract.localSymbol or contract.symbol,
                    }
                )

            def positionEnd(self):
                self.positions_done.set()

            def error(self, reqId, code, msg, advancedOrderRejectJson=""):
                if code in (2104, 2106, 2107, 2158):
                    return  # information de connexion aux fermes de données
                self.errors.append(f"{code}: {msg}")
                if code in FATAL_CODES:
                    self.fatal_error = f"{code}: {msg}"

        self.client = _Client()

    def collect(self, host: str, port: int, client_id: int, timeout: int) -> dict:
        client = self.client
        try:
            client.connect(host, port, clientId=client_id)
            threading.Thread(target=client.run, daemon=True).start()

            if not client.connected_flag.wait(min(10, timeout)):
                raise TimeoutError(f"pas de nextValidId du Gateway {host}:{port}")
            client.reqAccountSummary(1, "All", TAGS)
            client.reqPositions()

            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if client.summary_done.is_set() and client.positions_done.is_set():
                    break
                if client.fatal_error:
                    break
                time.sleep(0.2)

            require_complete(
                client.summary_done.is_set(),
                client.positions_done.is_set(),
                client.fatal_error,
            )

            return {
                "account": client.account,
                "currency": client.currency,
                "summary": dict(client.summary),
                "positions": list(client.positions),
                "connected": True,
                "errors": list(client.errors),
            }
        finally:
            try:
                client.disconnect()
            except Exception:  # noqa: BLE001 - la déconnexion ne masque jamais l'erreur d'origine
                pass


def build_payload(account_id: str, data: dict) -> dict:
    now = datetime.now(timezone.utc)
    summary = data.get("summary") or {}

    account: dict[str, object] = {"currency": data.get("currency") or "CAD"}
    for key, tag in ACCOUNT_TAGS.items():
        value = summary.get(tag)
        # Absent ou sentinel IBKR (« indisponible ») : la clé est omise, jamais remplacée par 0.
        if value is not None and value != UNAVAILABLE:
            account[key] = value

    return {
        "snapshot_id": f"ibkr:{account_id}:{int(now.timestamp() * 1000)}",
        "broker": "ibkr",
        "account_id": account_id,
        "environment": "paper" if account_id.upper().startswith("DU") else "live",
        "captured_at_utc": now.isoformat().replace("+00:00", "Z"),
        "source": SOURCE,
        "connection": {
            "connected": bool(data.get("connected", True)),
            "gateway": f"{os.environ.get('IBKR_GATEWAY_HOST', '127.0.0.1')}:"
            f"{os.environ.get('IBKR_GATEWAY_PORT', '4002')}",
        },
        "account": account,
        "fx_rates": {},
        "positions": data.get("positions") or [],
        "open_orders": [],
        # Remonté pour que le hub voie ce que le Gateway a signalé, au lieu d'un silence.
        "gateway_errors": data.get("errors") or [],
    }


def post(url: str, token: str, body: dict, timeout: int = 20) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
        # Hub éteint, DNS cassé, coupure réseau : un échec propre, pas un traceback.
        return 0, f"transport: {exc}"


def selftest() -> int:
    """Vérifie le contrat de payload sans Gateway ni réseau."""
    fake = {
        "account": "DUR967322",
        "currency": "CAD",
        "summary": {
            "NetLiquidation": 11282.16,
            "TotalCashValue": 11282.16,
            "BuyingPower": 37606.92,
            "AvailableFunds": 11282.07,
            "InitMarginReq": 0.09,
            "MaintMarginReq": 0.09,
            "GrossPositionValue": 0.0,
            "UnrealizedPnL": -12.5,
            "RealizedPnL": 3.25,
        },
        "positions": [
            {
                "symbol": "EUR",
                "side": "LONG",
                "quantity": 25000.0,
                "average_price": 1.0842,
                "sec_type": "CASH",
                "currency": "USD",
                "local_symbol": "EUR.USD",
            }
        ],
        "connected": True,
        "errors": [],
    }
    payload = build_payload("DUR967322", fake)
    checks = {
        "broker=ibkr": payload["broker"] == "ibkr",
        "environment=paper": payload["environment"] == "paper",
        "snapshot_id préfixé": payload["snapshot_id"].startswith("ibkr:DUR967322:"),
        "nav reprise": payload["account"]["net_liquidation"] == 11282.16,
        "P&L latent signé": payload["account"]["unrealized_pl"] == -12.5,
        "zéro réel conservé": payload["account"]["gross_position_value"] == 0.0,
        "currency CAD": payload["account"]["currency"] == "CAD",
        "1 position": len(payload["positions"]) == 1,
        "position LONG": payload["positions"][0]["side"] == "LONG",
        "gateway renseigné": bool(payload["connection"]["gateway"]),
        "connected propagé": payload["connection"]["connected"] is True,
    }

    # Une métrique absente ne doit JAMAIS devenir 0.0 : la clé doit être omise.
    empty = dict(fake, summary={}, positions=[], currency="")
    empty_payload = build_payload("DUR967322", empty)
    checks["devise par défaut CAD"] = empty_payload["account"]["currency"] == "CAD"
    checks["positions vides"] = empty_payload["positions"] == []
    checks["nav absente, pas 0"] = "net_liquidation" not in empty_payload["account"]
    checks["cash absent, pas 0"] = "total_cash" not in empty_payload["account"]

    # Le sentinel IBKR (métrique indisponible) ne doit pas être poussé tel quel.
    sentinel = build_payload("DUR967322", dict(fake, summary={"NetLiquidation": UNAVAILABLE}))
    checks["sentinel filtré"] = "net_liquidation" not in sentinel["account"]

    # Un Gateway en erreur fatale ne doit pas être annoncé « connecté ».
    down = build_payload("DUR967322", dict(fake, connected=False, errors=["502: lost"]))
    checks["déconnexion propagée"] = down["connection"]["connected"] is False
    checks["erreurs remontées"] = down["gateway_errors"] == ["502: lost"]

    # Pouvoir du test : require_complete doit refuser un flux partiel et une erreur fatale.
    def raises(fn, exc_type) -> bool:
        try:
            fn()
        except exc_type:
            return True
        except Exception:  # noqa: BLE001
            return False
        return False

    checks["flux partiel refusé"] = raises(lambda: require_complete(True, False, None), TimeoutError)
    checks["summary absent refusé"] = raises(lambda: require_complete(False, True, None), TimeoutError)
    checks["erreur fatale refusée"] = raises(
        lambda: require_complete(True, True, "502: lost"), ConnectionError
    )
    checks["état complet accepté"] = not raises(lambda: require_complete(True, True, None), Exception)

    # post() ne doit jamais lever : une panne de transport devient un échec propre.
    status, detail = post("http://127.0.0.1:9/api", "t", {"a": 1}, timeout=2)
    checks["transport -> status 0"] = status == 0
    checks["transport -> pas de traceback"] = "transport:" in detail

    failed = [name for name, ok in checks.items() if not ok]
    for name, ok in checks.items():
        print(f"{'ok  ' if ok else 'FAIL'} {name}")
    print(f"\n{len(checks) - len(failed)}/{len(checks)} vérifications passées")
    return 0 if not failed else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="imprime le payload, n'envoie rien")
    parser.add_argument("--selftest", action="store_true", help="vérifie le payload sans Gateway ni réseau")
    parser.add_argument("--url", default=None, help="override HERMES_WEB_URL")
    parser.add_argument("--timeout", type=int, default=20)
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    load_env_file(Path(os.environ.get("HERMES_IBKR_ENV", Path.home() / ".config/hermes-ibkr/publisher.env")))

    host = os.environ.get("IBKR_GATEWAY_HOST", "127.0.0.1")
    port = int(os.environ.get("IBKR_GATEWAY_PORT", "4002"))
    account_id = os.environ.get("IBKR_ACCOUNT_ID", "")
    client_id = int(os.environ.get("HERMES_IBKR_CLIENT_ID", "90"))
    base_url = (args.url or os.environ.get("HERMES_WEB_URL", "")).rstrip("/")
    token = os.environ.get("HERMES_WEB_TOKEN", "")

    if not account_id:
        print("IBKR_ACCOUNT_ID manquant", file=sys.stderr)
        return 2

    read_error: str | None = None
    try:
        with socket.create_connection((host, port), timeout=5):
            pass
        data = Probe().collect(host, port, client_id, args.timeout)
    except Exception as exc:  # noqa: BLE001 - un seul cycle, message clair
        read_error = str(exc) or exc.__class__.__name__
        print(f"Lecture IBKR échouée : {read_error}", file=sys.stderr)
        # On publie quand même un état DÉGRADÉ : sans ça, le hub garderait le dernier
        # « connected: true » indéfiniment et afficherait un Gateway vivant qui ne l'est plus.
        data = {
            "account": account_id,
            "currency": "CAD",
            "summary": {},
            "positions": [],
            "connected": False,
            "errors": [read_error],
        }

    payload = build_payload(data["account"] or account_id, data)

    if args.dry_run:
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if read_error is None else 1

    if not base_url or not token:
        print("HERMES_WEB_URL et HERMES_WEB_TOKEN sont requis", file=sys.stderr)
        return 2

    status, response = post(base_url + SNAPSHOT_PATH, token, payload, timeout=args.timeout)
    published = 200 <= status < 300
    print(f"{'OK ' if published else 'ERR'} {status} {SNAPSHOT_PATH} {response[:200]}")
    return 0 if published and read_error is None else 1


if __name__ == "__main__":
    raise SystemExit(main())
