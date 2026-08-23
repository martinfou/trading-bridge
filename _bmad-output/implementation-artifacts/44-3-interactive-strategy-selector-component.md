# Story 44.3: Interactive `<StrategySelector.vue>` Component with Search & Filter Chips

Status: ready-for-dev

## Story

As a trader,
I want an interactive, searchable `<StrategySelector.vue>` component with quick filter chips, search input, and keyboard navigation,
So that I can find and select any trading strategy in seconds without scrolling through a massive unorganized dropdown.

## Acceptance Criteria

1. **Given** `<StrategySelector.vue>`:
   - Renders a trigger button displaying the selected strategy's name, family badge, and compatible asset icon.
2. **When** the user clicks the trigger or presses `/` or `Cmd+K`:
   - A dropdown / modal popover opens with:
     - **Top Filter Chips**: `[All]`, `[⚡ Futures (IBKR)]`, `[💱 Forex]`, `[📈 Equities]`, `[Prop Desk]`, `[Long-Term]`, `[Trend]`, `[Breakout]`.
     - **Instant Search Input**: with auto-focus, placeholder `"Search by name, indicator (EMA, ATR, RSI), or asset..."`, and a clear button.
     - **Categorized Results List**: Grouped by Asset Class / Strategy Family with badges, 1-line description, and indicator chips.
3. **When** using keyboard navigation:
   - `Arrow Down` / `Arrow Up` highlights items in the filtered list.
   - `Enter` selects the highlighted strategy and closes the popover.
   - `Esc` closes the popover without changing selection.
4. **And** (Red Team Hardening: Focus Restoration & ARIA Combobox):
   - Closing the popover (via `Esc`, `Enter`, or backdrop click) immediately returns DOM focus to `triggerButtonRef` to prevent lost keyboard focus.
   - Popover implements `role="dialog"` or `role="combobox"` with `aria-expanded` and `aria-activedescendant`.
5. **And** the component is fully styled according to the dark theme design system (`var(--bg-primary)`, `var(--accent)`, `var(--border)`).

## Tasks / Subtasks

- [ ] **Task 1: Component Template & Styles (`desktop/src/components`)** (AC: 1, 2, 5)
  - [ ] Build `<StrategySelector.vue>` with trigger bar, popover, filter chips bar, and search box.
  - [ ] Create clean card layout for each strategy item with indicator tags and metadata badges.
- [ ] **Task 2: Keyboard Navigation & Focus Management** (AC: 3, 4)
  - [ ] Implement `keydown` event listeners for Arrow navigation, Enter selection, and Escape dismissal.
  - [ ] Implement WAI-ARIA focus management and clean focus return to trigger element.
- [ ] **Task 3: Visual & Accessibility Testing** (AC: 4, 5)
  - [ ] Test on desktop viewport with dark theme contrast verification and keyboard-only operation.
