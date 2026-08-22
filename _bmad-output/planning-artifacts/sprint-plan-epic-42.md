# Sprint Plan: Epic 42 — Desktop Frontend Multi-Asset UI Overhaul, WFA Hub Design & Look-and-Feel Polish

**Date:** 2026-08-22  
**Scope:** 
- **Epic 42:** Desktop Frontend Multi-Asset UI Overhaul, WFA Hub Design & Look-and-Feel Polish
  - Story 42.1: WFA Hub Redesign (Split-Screen, Presets, Parameter Sliders, Timeline & Stability Heatmap) & Vue Fix
  - Story 42.2: Multi-Asset Data Manager Matrix (Forex, CME Futures, US Equities) & Provider Switcher
  - Story 42.3: Live Trading Desk Margin Utilization Gauges & Multi-Broker Switcher (OANDA vs IBKR)
  - Story 42.4: Strategy Catalog & Backtest History Multi-Asset Filtering & Direct WFA Trigger
  - Story 42.5: Global Design System Polish, Dark Institutional Palette, Typography & Micro-Animations

---

## 🎯 Sprint Objectives & Architecture Invariants

1. **Native Dark Design Tokens**: All components MUST use CSS custom variables (`--bg-primary: #0a0a0a;`, `--bg-secondary: #141414;`, `--bg-card: #1a1a1a;`, `--accent: #d97706;`, `--asset-futures: #a855f7;`, `--asset-equity: #06b6d4;`, `--asset-forex: #f59e0b;`) without relying on missing Tailwind CSS classes.
2. **Zero Vue Warnings & Errors**: All icons, emits, and props must be strictly typed and registered to ensure 0 console errors/warnings in Electron.
3. **Institutional Multi-Asset Coverage**: Full parity for Forex (`EUR_USD`), CME Futures (`MES`, `M2K`, `EMD`, `MNQ`), and US Equities (`IWM`, `MDY`, `AAPL`, `SPY`, `QQQ`).

---

## 📋 Story Execution Matrix

| Story | Focus Area | Key Deliverables | Status |
| :--- | :--- | :--- | :--- |
| **42.1** | WFA Hub View | Split-screen layout, presets, parameter sliders, combinations count, KPI cards, timeline/stability embed | Ready |
| **42.2** | Data Manager | Category tabs (Forex / Futures / Stocks), provider toggle (Yahoo/Dukascopy/OANDA/IBKR), 2006-2026 matrix | Ready |
| **42.3** | Live Trading Desk | Broker tabs (OANDA/IBKR), margin utilization meter, PDT counter, safety lockouts | Ready |
| **42.4** | Strategies & Backtests | Multi-asset color badges, direct "Run WFA" buttons, calibration freshness pills | Ready |
| **42.5** | Design System | Global theme tokens, tabular numerals, glassmorphism cards, micro-animations | Ready |
