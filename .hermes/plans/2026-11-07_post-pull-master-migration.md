# Post-Pull `master` Migration Plan (2026-11-07)

> **For Hermes:** Follow `.hermes/INSTRUCTIONS.md` step-by-step upon pulling `master`.

See [`.hermes/INSTRUCTIONS.md`](../INSTRUCTIONS.md) for the complete command sequence to:
1. Purge untracked `data/runtime/generator/databank.json` and `promoted.json` so `strategies.db` stays at 34 WFA-validated strategies.
2. Migrate `data-futures/` fills and cursors into unified `data/fills/` and `data/state/ibkr-paper/`.
3. Retire legacy containers (`trading-dev`, `trading-live`, `trading-futures`).
4. Rebuild and restart `trading-control-plane.service`.
5. Start `oanda-paper` (`docker/docker-compose.oanda.yml`) and `ibkr-paper` (`docker/docker-compose.ibkr.yml`).
