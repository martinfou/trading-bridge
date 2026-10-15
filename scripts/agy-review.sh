#!/usr/bin/env bash
# =============================================================================
# agy-review.sh — independent second-opinion code review via the agy CLI.
#
# Why this exists: two reviewers from the same model family share the same blind
# spots. On 2026-09-30 this wrapper's target commit was reviewed by a deepseek
# agent AND by agy (a different family): agy independently confirmed 4 of the 6
# findings and surfaced 2 more that nobody else saw — including a BLOCKER where a
# reconciliation stuck on "trade still open at the broker" permanently blocks
# every future entry order (the strategy becomes a zombie that never trades).
# See docs/TRADING-GUARDRAILS.md.
#
# Usage:  agy-review.sh <git-ref> [extra instructions...]
# Env:    REPO (default: ~/projects/trading-bridge), AGY_MODEL, AGY_EFFORT
# Exit:   0 = no BLOCKER/MAJOR   |   1 = BLOCKER/MAJOR found   |   2 = agy unusable
#
# Guarantees: read-only (isolated detached worktree, removed on exit), no MCP, no
# web, no writes into the live tree, bounded runtime, and the raw review is kept
# on disk for the audit trail.
# =============================================================================
set -uo pipefail

REPO="${REPO:-$HOME/projects/trading-bridge}"
REF="${1:?usage: agy-review.sh <git-ref> [extra instructions...]}"
shift || true
EXTRA="$*"

AGY="${AGY:-$HOME/.local/bin/agy}"
SCRATCH="${SCRATCH:-$HOME/.hermes/cache/scratch}"
OUTDIR="$SCRATCH/agy-reviews"
mkdir -p "$OUTDIR"

LIVE=0
[[ "$REF" == "--live" ]] && LIVE=1

STAMP=$(date +%Y%m%d-%H%M%S)

if [[ $LIVE -eq 1 ]]; then
  # Review the UNCOMMITTED working tree (that is what is about to be committed).
  #
  # ⚠️ Documented limitation, measured 2026-09-30: agy resolves files against the project root
  # registered in ~/.gemini/projects.json, NOT against the current directory. A review "of a ref"
  # therefore still reads the live working tree, which is why an isolated worktree does not
  # actually isolate anything for this tool. --live makes that explicit instead of pretending.
  REF="HEAD"
  SHORT="live-$(git -C "$REPO" rev-parse --short HEAD)"
  SUBJECT="uncommitted working tree on branch $(git -C "$REPO" rev-parse --abbrev-ref HEAD)"
  WORKTREE="$REPO"
  OUT="$OUTDIR/$SHORT-$STAMP.txt"
  echo "── agy independent review (LIVE working tree) ────────────────────────────"
else
  SHORT=$(git -C "$REPO" rev-parse --short "$REF") || { echo "agy-review: unknown ref '$REF'"; exit 2; }
  SUBJECT=$(git -C "$REPO" log -1 --pretty=%s "$REF")
  OUT="$OUTDIR/$SHORT-$STAMP.txt"
  WORKTREE=$(mktemp -d "$SCRATCH/agy-review-XXXXXX")
  cleanup() {
    git -C "$REPO" worktree remove --force "$WORKTREE" >/dev/null 2>&1 || rm -rf "$WORKTREE"
  }
  trap cleanup EXIT
  echo "── agy independent review ────────────────────────────────────────────────"
fi

echo "   repo     : $REPO"
echo "   ref      : $SHORT  ($SUBJECT)"
echo "   worktree : $WORKTREE$([[ $LIVE -eq 1 ]] && echo '  (live tree — agy ignores worktree isolation)')"
echo "   output   : $OUT"
echo

if [[ $LIVE -eq 0 ]]; then
  git -C "$REPO" worktree add --detach "$WORKTREE" "$REF" >/dev/null 2>&1 || {
    echo "agy-review: could not create the review worktree"; exit 2; }
fi

# The prompt is pinned and tightly scoped on purpose: agy goes on MCP/Web
# discovery odysseys when a prompt is broad (documented pitfall), and it must
# never modify anything.
#
# Prompt discipline is what makes this useful, and the first version of this
# wrapper proved it: a generic "review the commit" prompt returned 1 MINOR and
# APPROVED on a commit where a file-scoped, invariant-first prompt (the text
# below) had found 2 BLOCKERs. A weak prompt does not just miss findings, it
# hands back a false pass — so the invariant and the file list are mandatory.
CHANGED=$(git -C "$WORKTREE" diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null \
          | grep -vE '\.(md|txt|json)$' | head -20)
[[ -z "$CHANGED" ]] && CHANGED=$(git -C "$WORKTREE" show --name-only --pretty=format: HEAD | grep -vE '\.(md|txt|json)$' | head -20)

INVARIANT="${INVARIANT:-There is no stated invariant. Infer what this code promises to guarantee from its tests and its comments, state that promise in one line, and then judge the code against it.}"

PROMPT=$(cat <<PROMPT_EOF
You are an independent, adversarial CODE REVIEWER. Work only from the repository
files in this directory. Do NOT modify any file. Do NOT create files. Do NOT
search the web. Do NOT use Joplin or any MCP tool. Do NOT run git commands that
change state.

THE INVARIANT THIS CODE MUST PROTECT:
$INVARIANT

THE FILES THAT MATTER (read every one of them completely, not just the diff —
the bugs live in the interaction between new and old code):
$CHANGED

Start with \`git show HEAD\` to see what changed, then read the full files.

Hunt specifically for:
  - values counted twice, or a value from the wrong source/currency reaching an accumulator;
  - resource growth without bound (memory, state-file size, broker/API call volume);
  - work done on a latency-critical thread, or a slow call inside a lock;
  - thread-safety: unsynchronized mutation of shared collections, check-then-act
    races, non-atomic or unsynchronized writes of persistent state;
  - failure modes: a terminal state that silently blocks later work, a missing or
    malformed broker response treated as a legitimate value, retry paths that end
    in a silent no-op;
  - tests that pass vacuously, or behaviour with no test at all.

Assume the code IS broken and your job is to prove it: it was written by an agent
that reported it as finished and green, and the tests passed. Rate each finding
BLOCKER (data loss / silently stops trading / corrupts state), MAJOR (wrong
behaviour or a real race under plausible timing) or MINOR (real but bounded). If
you genuinely find nothing at a severity, say so — do not invent findings.

REVIEW BY READING ONLY. Do NOT run the build, the tests, git commands, or any
command that takes more than a few seconds — a run that spends its budget
executing something and returns no verdict is a failed review, worse than no
review at all. Your entire output must be the findings and the verdict line.

Output format, nothing else:
  <SEVERITY> - <file>:<line> - <the problem in one sentence> - <the smallest fix>
one line per finding, at most 10 findings, then a final line:
  VERDICT: APPROVED | NEEDS_FIX
PROMPT_EOF
)
if [[ -n "$EXTRA" ]]; then
  PROMPT="$PROMPT

Additional instructions for this review: $EXTRA"
fi

# Pin the reviewer, so the gate does not silently change model when agy updates its own default
# (measured 2026-09-30: unset AGY_MODEL meant an unreviewed drift to whatever agy defaulted to).
# Gemini is covered by Martin's Google/Antigravity subscription, so it is the default.
# Claude credits are limited: opt in explicitly when the diff really warrants it,
# e.g. AGY_MODEL=claude-opus-4-6-thinking scripts/agy-review.sh <ref>
AGY_MODEL="${AGY_MODEL:-gemini-3.1-pro-high}"
MODEL_ARGS=()
[[ -n "${AGY_MODEL:-}" ]] && MODEL_ARGS+=(--model "$AGY_MODEL")
[[ -n "${AGY_EFFORT:-}" ]] && MODEL_ARGS+=(--effort "$AGY_EFFORT")

# Bounded runtime; --print-timeout 0 (the CLI default) waits forever, which would
# hang the deploy gate if agy stalls explaining its own flags.
timeout 300 "$AGY" --print "$PROMPT" --dangerously-skip-permissions \
  --print-timeout 240s "${MODEL_ARGS[@]}" 2>&1 | tee "$OUT"
RC=${PIPESTATUS[0]}

if [[ $RC -ne 0 ]] || [[ ! -s "$OUT" ]]; then
  echo
  echo "agy-review: agy exited $RC with no usable output (tool problem, not a verdict)"
  exit 2
fi

echo
echo "── summary ───────────────────────────────────────────────────────────────"
BLOCKERS=$(grep -ciE '^\**BLOCKER' "$OUT" || true)
MAJORS=$(grep -ciE '^\**MAJOR' "$OUT" || true)
MINORS=$(grep -ciE '^\**MINOR' "$OUT" || true)
# Accept the whole family of non-pass words, not only NEEDS_FIX. A reviewer that
# writes "VERDICT: REJECTED" IS stating a fail, and reporting that as
# "<not stated>" hides the reason from the operator. Measured 2026-10-01: a
# REJECTED carrying 1 BLOCKER + 1 MAJOR was summarised as "no verdict line",
# which reads like a tool crash instead of a rejection.
# The safe direction is unchanged: anything unrecognised still fails the gate.
VERDICT=$(grep -oE 'VERDICT: *(APPROVED|NEEDS_FIX|REJECTED|REJECTS|REJECT|FAIL|FAILED)' "$OUT" | tail -1 || true)
STATED=$(grep -coE 'VERDICT:' "$OUT" || true)
echo "   BLOCKER: $BLOCKERS   MAJOR: $MAJORS   MINOR: $MINORS"
echo "   ${VERDICT:-VERDICT: <not stated by the reviewer>}"
if [[ -z "$VERDICT" && "${STATED:-0}" -gt 0 ]]; then
  echo "   ⚠ the reviewer DID write a VERDICT line, but not in the accepted vocabulary:"
  echo "     $(grep -oE 'VERDICT:.*' "$OUT" | tail -1)"
fi
echo "   raw review kept at: $OUT"

# A review with no verdict line is a FAILED review, never a pass. This was a real
# bug on 2026-09-30: agy spent its whole budget running the test suite instead of
# reviewing, returned no findings, and this script reported "gate: pass" on an
# empty review — a false pass is worse than no gate, so it exits 2 (tool problem)
# and the caller must treat the review as missing, not as clean.
if [[ -z "$VERDICT" ]]; then
  echo "   → gate: ERROR — the reviewer returned no verdict line."
  echo "     This is a tool failure, NOT an approval. Treat the change as unreviewed."
  echo "     (Check that the prompt forbids running the build/tests, and retry.)"
  exit 2
fi

if [[ $BLOCKERS -gt 0 || $MAJORS -gt 0 ]]; then
  echo "   → gate: FAIL (BLOCKER/MAJOR must be triaged before deploy)"
  exit 1
fi
echo "   → gate: pass on this reviewer's verdict"
exit 0
