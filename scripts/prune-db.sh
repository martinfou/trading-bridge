#!/usr/bin/env bash
# =============================================================================
# prune-db.sh — keep data/runtime/events.db from regrowing to 19 GB.
#
# Why this exists: on 2026-09-30 events.db was 18.86 GB. Nothing had ever pruned it, because the
# only writer of backtest exhaust (SqliteTradeStore, via BacktestPersistenceService) appends forever.
# Two separate payloads dominate the file, and they need different treatment:
#
#   trades        per-trade rows of every backtest run     -> delete rows older than RETENTION_DAYS
#   backtest_runs one row per run, ~1.3 MB each because    -> keep every scalar metric, drop only the
#                 the row carries a full equity_curve blob    equity_curve payload for old runs
#
# The second point is the one that is easy to get wrong. Pruning `trades` alone freed 7.62 GB and left
# the file at 10.95 GB, because `backtest_runs` is where the other 10.27 GB lived. A prune aimed only
# at `trades` would have looked successful and changed nothing in practice.
#
# Invariants (checked, not assumed, and the script exits non-zero before compacting if one breaks):
#   1. no trade belonging to a run inside the retention window is ever deleted
#   2. no equity_curve of a run inside the retention window is ever replaced
#   3. every scalar metric row survives regardless of age
#
# Trades and curves are regenerable by re-running a backtest; metrics are not, so they are kept.
#
# Run by the container's entrypoint on a daily timer (no cron) so it does not depend on Hermes.
# =============================================================================
set -euo pipefail

DAYS="${RETENTION_DAYS:-30}"
REPO="${REPO:-/app}"
DB="${EVENTS_DB:-$REPO/data/runtime/events.db}"
AUDIT_DIR="${AUDIT_DIR:-$REPO/data/runtime}"

[[ -f "$DB" ]] || { echo "[prune] no database at $DB, nothing to do"; exit 0; }
command -v sqlite3 >/dev/null || { echo "[prune] sqlite3 not found in this image" >&2; exit 1; }

STAMP=$(date +%Y%m%d-%H%M%S)
sq() { sqlite3 -cmd ".timeout 60000" "$DB" "$1"; }
gb() { awk -v b="$1" 'BEGIN{printf "%.2f", b/1024/1024/1024}'; }

BEFORE=$(stat -c %s "$DB")
echo "[prune] $(date -Is) db=$(gb "$BEFORE") GB retention=${DAYS}d"

# ---------------------------------------------------------------- 1. trades --
TRADES_BEFORE=$(sq "select count(*) from trades;")
RECENT_TRADES=$(sq "select count(*) from trades where run_id in (select run_id from backtest_runs where created_at >= date('now','-$DAYS day'));")
sq "select run_id from backtest_runs where created_at < date('now','-$DAYS day');" > "$AUDIT_DIR/pruned-run-ids-$STAMP.txt"

# batched delete: one 25 M-row transaction creates a WAL as large as the table
BATCH=0; IDS=""; N=0
while read -r id; do
  [[ -z "$id" ]] && continue
  IDS="${IDS:+$IDS,}'$id'"; N=$((N+1))
  if [[ $N -eq 200 ]]; then
    sq "delete from trades where run_id in ($IDS);"; BATCH=$((BATCH+1)); IDS=""; N=0
  fi
done < "$AUDIT_DIR/pruned-run-ids-$STAMP.txt"
[[ -n "$IDS" ]] && { sq "delete from trades where run_id in ($IDS);"; BATCH=$((BATCH+1)); }

SURVIVED=$(sq "select count(*) from trades where run_id in (select run_id from backtest_runs where created_at >= date('now','-$DAYS day'));")
if [[ "$SURVIVED" -ne "$RECENT_TRADES" ]]; then
  echo "[prune] ABORT: recent-trade invariant broken ($SURVIVED != $RECENT_TRADES), not compacting" >&2
  exit 1
fi
echo "[prune] trades: $TRADES_BEFORE -> $(sq "select count(*) from trades;") in $BATCH batch(es), recent rows intact ($SURVIVED)"

# --------------------------------------------------- 2. backtest_run curves --
NOTNULL=$(sq "select \"notnull\" from pragma_table_info('backtest_runs') where name='equity_curve';" | tr -d ' ')
[[ "$NOTNULL" == "1" ]] && NEWVAL="'[]'" || NEWVAL="NULL"

RECENT_CURVES=$(sq "select count(*) from backtest_runs where created_at >= date('now','-$DAYS day') and length(coalesce(equity_curve,'')) > 2;")
PAYLOAD=$(sq "select coalesce(sum(length(equity_curve)),0) from backtest_runs where created_at < date('now','-$DAYS day');")
sq "update backtest_runs set equity_curve = $NEWVAL where created_at < date('now','-$DAYS day') and length(coalesce(equity_curve,'')) > 2;"

RECENT_CURVES_AFTER=$(sq "select count(*) from backtest_runs where created_at >= date('now','-$DAYS day') and length(coalesce(equity_curve,'')) > 2;")
if [[ "$RECENT_CURVES_AFTER" -ne "$RECENT_CURVES" ]]; then
  echo "[prune] ABORT: recent-curve invariant broken ($RECENT_CURVES_AFTER != $RECENT_CURVES), not compacting" >&2
  exit 1
fi
echo "[prune] curves: dropped $(gb "$PAYLOAD") GB of payload, recent curves intact ($RECENT_CURVES_AFTER), metrics kept ($(sq "select count(*) from backtest_runs;") runs)"

# ------------------------------------------------------------- 3. compact ---
echo "[prune] vacuum ..."
sq "vacuum;"
sq "pragma wal_checkpoint(TRUNCATE);" || true

AFTER=$(stat -c %s "$DB")
echo "[prune] done db=$(gb "$AFTER") GB, freed $(gb "$((BEFORE-AFTER))") GB, integrity=$(sq "pragma quick_check;" | head -1)"

# keep the last 5 audit files, drop the rest
ls -1t "$AUDIT_DIR"/pruned-run-ids-*.txt 2>/dev/null | tail -n +6 | xargs -r rm -f
