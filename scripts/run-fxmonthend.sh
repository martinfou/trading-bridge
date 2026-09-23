#!/bin/bash
# Compile + run the FX month-end flow pre-validation (Pattern D, 41e résultat Simons).
# Usage: ./scripts/run-fxmonthend.sh
exec "$(dirname "$0")/run-research-check.sh" com.martinfou.trading.intelligence.research.FxMonthEndFlowCheck
