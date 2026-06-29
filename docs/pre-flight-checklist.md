# Pre‑Flight Checklist

Before starting any backtest, paper or live run, ensure the following items are verified:

- **Credentials**: OANDA_API_KEY and OANDA_ACCOUNT_ID are set in the environment (or loaded via password manager). IBKR credentials are present if using IBKR.
- **Broker Config**: `broker-accounts.json` lists the target account and matches the intended mode (PAPER / LIVE).
- **Network**: Ability to reach broker endpoints (`ping api-fxpractice.oanda.com`, `curl https://api-fxpractice.oanda.com/v3/accounts/{id}`) without timeouts.
- **DB Health**: Run `sqlite3 data/runtime/events.db "PRAGMA integrity_check;"` – no errors.
- **Backup Availability**: Latest daily backup present in `data/runtime/backups/`.
- **Control Plane**: `curl http://localhost:8080/api/health` returns **200**.
- **Configuration Version**: `git rev-parse HEAD` matches the version deployed on the control plane.
- **Risk Limits**: Verify `risk-limits.json` contains sensible thresholds for the strategy.
- **Promote Gates**: Ensure golden backtest passes (`GET /api/strategies/{id}/promote-readiness`).

If any check fails, resolve before proceeding.
