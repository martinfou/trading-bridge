#!/usr/bin/env python3
"""OANDA read-only smoke test — part of the pre-deploy gate.

Verifies that the credentials the PAPER containers are deployed with are present,
that the practice account answers, and that it is a CAD account. Sends no order: it
reads the account summary only. A non-zero exit fails the gate.

Credential source, in order:
  1. $TB_ENV_FILE, if set
  2. <repo>/.env.paper   <- what the paper containers are actually deployed with
  3. ~/.hermes/.env      <- fallback only, and it WARNS when used

Why the order is this way: the gate used to read ~/.hermes/.env only. That file held
a DIFFERENT key and a DIFFERENT account (the dormant -012) from the one being
deployed, so a green gate proved nothing about the credentials actually going out,
and when that stale key expired the gate went permanently red for a reason unrelated
to the code under test. A gate that fails for the wrong reason gets bypassed, and a
gate that passes for the wrong reason is worse. The account is always printed so a
mismatch is visible instead of silent.
"""
import json
import os
import urllib.request
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def candidates():
    explicit = os.environ.get("TB_ENV_FILE")
    return [p for p in (explicit,
                        os.path.join(REPO, ".env.paper"),
                        os.path.expanduser("~/.hermes/.env")) if p]


def load_env(path: str) -> dict:
    env = {}
    if not os.path.exists(path):
        return env
    with open(path, errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def main() -> int:
    env = {}
    used = None
    for path in candidates():
        env = load_env(path)
        if env.get("OANDA_API_KEY") and env.get("OANDA_ACCOUNT_ID"):
            used = path
            break

    key = env.get("OANDA_API_KEY")
    account = env.get("OANDA_ACCOUNT_ID")
    host = env.get("OANDA_REST_URL", "https://api-fxpractice.oanda.com")

    if not used:
        print("   ✗ no credential file with OANDA_API_KEY + OANDA_ACCOUNT_ID; looked at:")
        for p in candidates():
            print(f"     - {p}")
        return 1

    if used != os.path.join(REPO, ".env.paper") and not os.environ.get("TB_ENV_FILE"):
        print(f"   ⚠ not the deploy source: credentials came from {used}, not .env.paper")

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
        print(f"     source {used} | account {account}")
        return 1

    # The whole accounting rule assumes the account is in CAD: realized P&L is stored as
    # broker-sourced ACCOUNT-currency. A USD practice account would silently pass the gate.
    if acct.get("currency") != "CAD":
        print(f"   ✗ account currency is {acct.get('currency')}, expected CAD — the realized-P&L")
        print("     ledger assumes account currency; check the account ID before deploying")
        return 1

    print(f"   → account {account} ({acct['currency']}) reachable")
    print(f"     balance {acct['balance']} | NAV {acct['NAV']} | open trades {acct['openTradeCount']}")
    print(f"     credentials from {used}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
