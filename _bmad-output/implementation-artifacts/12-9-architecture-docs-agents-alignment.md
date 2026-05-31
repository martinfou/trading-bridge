# Story 12.9: Architecture, Docs & Agents Alignment

Status: done

## Story

As a developer or AI agent,
I want architecture and agent docs aligned with the current codebase,
so that onboarding and implementation guidance match reality after Epic 12–13 consolidation.

## Acceptance Criteria

- [x] Canonical architecture doc reflects all Maven modules and runtime control plane
- [x] `AGENTS.md` module graph includes runtime, TUI, genetics; links architecture doc
- [x] `_bmad-output/project-context.md` updated (stack, backtest capabilities, key paths, active epics)
- [x] `docs/README.md` architecture section lists current modules
- [x] Sprint tracking note distinguishes BMAD epics from vision `docs/sprint-plan.md`

## Implementation

- **`docs/architecture.md`** — module DAG, mermaid diagram, entry points, data paths
- **`AGENTS.md`** — full module table, control plane / TUI commands, golden baseline pointer
- **`project-context.md`** — Epic 12/13 state, SL/TP + commission in backtest, runtime paths
- **`docs/README.md`** — architecture tree + modules table (runtime, strategies, data, genetics, tui)
- **`docs/sprint-plan.md`** — banner → `sprint-status.yaml` for implementation tracking

## References

- Epic 12 consolidation plan
- Epic 13 platform runtime (control plane, TUI, dashboard)
