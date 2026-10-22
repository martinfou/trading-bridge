#!/usr/bin/env python3
"""M30 BA download (30-min granularity: 5000 bars = 104 days, so 2020->now is ~20 requests)."""
import importlib.util, os
from datetime import datetime, timezone
spec = importlib.util.spec_from_file_location(
    "fetch", os.path.expanduser("~/projects/tb-research/scripts/oanda-ba-fetch.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.STEP["M30"] = m.STEP["H1"] * 0  # placeholder replaced below
from datetime import timedelta
m.STEP["M30"] = timedelta(minutes=30)
end = datetime(2026, 10, 23, tzinfo=timezone.utc)
for inst in ["GBP_CHF", "AUD_CHF", "EUR_CHF"]:
    m.download(inst, "M30", datetime(2015, 1, 1, tzinfo=timezone.utc), end)
