# Story 13.7 — Laravel dashboard v1

Status: review

## Story

As Martin, I want a Laravel control room that polls the Java control plane so I can see exposure, daily drawdown, broker status, alerts, and trigger kill switch without curl.

## Acceptance Criteria

- [x] **AC1** — Laravel app in `dashboard/` (thin client, no trading logic)
- [x] **AC2** — Polls `GET /api/control/summary` + `/api/broker-accounts`
- [x] **AC3** — Shows runs (exposure), daily DD, stale flag, gap + drift signals
- [x] **AC4** — Kill switch `POST /api/strategies/{id}/kill` from UI
- [x] **AC5** — Auto-refresh + feature tests with `Http::fake`

## Tasks

- [x] Scaffold Laravel in `dashboard/`
- [x] `ControlPlaneClient` + `ControlRoomController`
- [x] Blade view `control-room`
- [x] `ControlRoomTest`
- [x] README + `docs/testing.md`

## File List

- `dashboard/` (Laravel app — vendor gitignored)
- `dashboard/app/Services/ControlPlaneClient.php`
- `dashboard/app/Http/Controllers/ControlRoomController.php`
- `dashboard/resources/views/control-room.blade.php`
- `dashboard/config/trading.php`
- `dashboard/tests/Feature/ControlRoomTest.php`

## Change Log

- 2026-05-30: Laravel control room v1
