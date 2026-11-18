#!/usr/bin/env python3
"""Contrat de code de sortie de `ibkr_snapshot_push.py` (hors ligne, sans effet de bord).

Trois cas sont vérifiés, en publiant vers un faux endpoint local (jamais vers hermes-web) :

| Cas | Gateway | POST | Sortie attendue |
|---|---|---|---|
| A — état dégradé publié | muet (port fermé) | 201 | 3 |
| B — échec réel | muet (port fermé) | 500 | 1 |
| C — cycle complet | vivant | 201 | 0 |

Le cas C est sauté (pas échoué) si aucun Gateway n'écoute sur `IBKR_GATEWAY_PORT`.

Usage:
    python3 scripts/tests/test_ibkr_snapshot_push_exit_codes.py

Le code 3 existe pour que `SuccessExitStatus=3` du service systemd ne compte pas une
indisponibilité du Gateway comme une panne du publieur (une panne de 21 h produisait
~1250 cycles « Failed » dans le journal).
"""
from __future__ import annotations

import http.server
import json
import os
import socket
import subprocess
import sys
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ibkr_snapshot_push.py"
PY = os.environ.get(
    "HERMES_IBKR_PY", str(Path.home() / ".local/share/hermes-ibkr/venv/bin/python")
)
RECEIVED: list[dict] = []


class Handler(http.server.BaseHTTPRequestHandler):
    status = 201

    def do_POST(self):  # noqa: N802 - API http.server
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        try:
            RECEIVED.append(json.loads(raw))
        except ValueError:
            RECEIVED.append({"_raw": raw[:200].decode(errors="replace")})
        self.send_response(Handler.status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"success":true}')

    def log_message(self, format, *args):  # noqa: A002 - signature de la classe de base
        pass


def cycle(env_extra: dict, label: str) -> tuple[int, dict]:
    env = dict(os.environ)
    env.update(env_extra)
    env["HERMES_IBKR_CLIENT_ID"] = "91"  # ne jamais emprunter le clientId 90 du timer
    proc = subprocess.run(
        [PY, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=180
    )
    print(f"\n=== {label} : exit={proc.returncode} ===")
    print("  " + (proc.stdout.strip() or "(stdout vide)").replace("\n", "\n  "))
    if proc.stderr.strip():
        print("  stderr: " + proc.stderr.strip().replace("\n", " | "))
    return proc.returncode, RECEIVED[-1] if RECEIVED else {}


def port_ouvert(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=1):
            return True
    except OSError:
        return False


def main() -> int:
    if not Path(PY).exists():
        print(f"interpréteur introuvable : {PY} (voir HERMES_IBKR_PY)")
        return 2

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    faux_hub = f"http://127.0.0.1:{server.server_address[1]}"
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port_mort = "4902"
    echecs: list[str] = []

    Handler.status = 201
    RECEIVED.clear()
    code, payload = cycle(
        {"IBKR_GATEWAY_PORT": port_mort, "HERMES_WEB_URL": faux_hub},
        "A état dégradé publié",
    )
    connected = (payload.get("connection") or {}).get("connected")
    if code != 3:
        echecs.append(f"A: exit {code} != 3")
    if connected is not False:
        echecs.append(f"A: connection.connected={connected!r} != False")

    Handler.status = 500
    RECEIVED.clear()
    code, _ = cycle(
        {"IBKR_GATEWAY_PORT": port_mort, "HERMES_WEB_URL": faux_hub},
        "B POST refusé",
    )
    if code != 1:
        echecs.append(f"B: exit {code} != 1")

    host = os.environ.get("IBKR_GATEWAY_HOST", "127.0.0.1")
    port = int(os.environ.get("IBKR_GATEWAY_PORT", "4002"))
    if port_ouvert(host, port):
        Handler.status = 201
        RECEIVED.clear()
        code, payload = cycle({"HERMES_WEB_URL": faux_hub}, "C cycle complet")
        connected = (payload.get("connection") or {}).get("connected")
        if code != 0:
            echecs.append(f"C: exit {code} != 0")
        if connected is not True:
            echecs.append(f"C: connection.connected={connected!r} != True")
    else:
        print(f"\n=== C sauté : aucun Gateway sur {host}:{port} ===")

    server.shutdown()
    if echecs:
        print("\nÉCHEC : " + " ; ".join(echecs))
        return 1
    print("\nOK — contrat de codes de sortie respecté")
    return 0


if __name__ == "__main__":
    sys.exit(main())
