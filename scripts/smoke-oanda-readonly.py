#!/usr/bin/env python3
"""OANDA read-only smoke test — part of the pre-deploy gate.

Verifies that the credentials the container will use are present and that the
practice account answers. Sent no order: it only reads the account summary and
the open trades count. Exit code != 0 means the gate fails.

Credentials are read from ~/.hermes/.env (never printed).
"""
import json
import os
import sys
import urllib.request
import urllib.parse

ENV = os.path.expanduser("~/.hermes/.env")


def load_env() -> dict:
    env = {}
    if not os.path.exists(ENV):
        return env
    with open(ENV, errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


def main() -> int:
    env = load_env()
    key = env.get("OANDA_API_KEY")
    account = env.get("OANDA_ACCOUNT_ID")
    host = env.get("OANDA_REST_URL", "https://api-fxpractice.oanda.com")

    missing = [n for n, v in (("OANDA_API_KEY", key), ("OANDA_ACCOUNT_ID", account)) if not v]
    if missing:
        print(f"   ✗ missing in ~/.hermes/.env: {', '.join(missing)}")
        return 1

    if "practice" not in host:
        print(f"   ⚠ host is not the practice endpoint ({host}) — refusing to smoke test a live account")
        return 1

    url = host.rstrip("/") + f"/v3/accounts/{account}/summary"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {key}"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            acct = json.load(resp)["account"]
    except Exception as exc:  # noqa: BLE001 - the gate only needs pass/fail
        print(f"   ✗ OANDA unreachable or rejected the credentials: {exc!r}")
        return 1

    print(f"   → account {account} ({acct['currency']}) reachable")
    print(f"     balance {acct['balance']} | NAV {acct['NAV']} | open trades {acct['openTradeCount']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
