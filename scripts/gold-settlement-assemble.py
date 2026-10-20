#!/usr/bin/env python3
"""Assemble le rapport du 62e : corps principal + les deux annexes (harnais separes)."""
import subprocess, os

H = os.path.expanduser("~/projects/tb-research")
rep = f"{H}/reports/2026-10-20-gold-settlement-anchor.txt"
main_txt = open(rep).read()

a = subprocess.run(["python3", f"{H}/scripts/gold-settlement-vendor-check.py"],
                   capture_output=True, text=True).stdout
b = subprocess.run(["python3", f"{H}/scripts/gold-settlement-p0-59e.py"],
                   capture_output=True, text=True).stdout

sep = "=" * 138
out = [main_txt.rstrip("\n"), "", sep,
       "--- 9. ANNEXE A (harnais gold-settlement-vendor-check.py) — CONTROLE FOURNISSEUR / INSTRUMENT",
       "    Le MEME bar [16,17) ET sur 3 crosses OANDA (celles du 59e) et 5 series Dukascopy, + controle +-1 h.",
       sep, "", a.strip("\n"), "", sep,
       "--- 10. ANNEXE B (harnais gold-settlement-p0-59e.py) — P0 : REJEU DU CHIFFRE DU 59e",
       "    Loi du 60e : executer le P0 dans le run, sur les fichiers d'origine.",
       sep, "", b.strip("\n"), ""]
open(rep, "w").write("\n".join(out) + "\n")
print("rapport assemble ->", rep, os.path.getsize(rep), "octets")
