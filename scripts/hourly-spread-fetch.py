#!/usr/bin/env python3
"""Fetch OANDA BA (bid+ask) H1 candles for the 6 symbols of RealCostModel.HALF_SPREAD_PRICE.

Purpose: build the ET hour-of-day spread profile that the constant half-spread model is missing.
Window chosen 2021-01-01 -> now: the hourly microstructure is a current property, and the era in
which the deployed lanes actually execute. Read-only, resumable.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib.machinery import SourceFileLoader
from datetime import datetime, timezone

mod = SourceFileLoader("ba", os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                          "oanda-ba-fetch.py")).load_module()

SYMBOLS = ["EUR_USD", "GBP_USD", "USD_JPY", "USD_CHF", "AUD_USD", "GBP_JPY", "XAU_USD"]

if __name__ == "__main__":
    end = datetime(2026, 10, 24, tzinfo=timezone.utc)
    for inst in SYMBOLS:
        mod.download(inst, "H1", datetime(2021, 1, 1, tzinfo=timezone.utc), end)
