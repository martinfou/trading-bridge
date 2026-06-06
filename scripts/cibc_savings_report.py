#!/usr/bin/env python3
"""Parse CIBC credit card CSV and generate a family savings PDF report."""

from __future__ import annotations

import csv
import re
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Iterable

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import (
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

# --- categorization rules (first match wins) ---

CATEGORY_RULES: list[tuple[str, re.Pattern[str]]] = [
    ("Payments & credits", re.compile(r"PAYMENT THANK YOU|PAIEMENT", re.I)),
    ("Subscriptions & telecom", re.compile(
        r"SPOTIFY|DROPBOX|GOOGLE \*|Garmin|ROGERS|VIRGIN PLUS|BELL |PrimeVideo|"
        r"Amazon Channels|Workspace|eero wifi|DESJARDINS QC VISA FOO|HP \*CANADA",
        re.I,
    )),
    ("Amazon & online retail", re.compile(r"AMZN|Amazon\.ca", re.I)),
    ("Groceries", re.compile(
        r"MAXI|SUPER C|COSTCO|IGA |METRO |WAL-MART|WINNERS|GIANT TIGER|"
        r"MARCHE DU VIEUX|LES ESCOMPTE|DOLLAR PLUSS|T&T SUPERMARKET|"
        r"GROUPE MAYRAND|GOPISCINE",
        re.I,
    )),
    ("Pharmacy & health", re.compile(
        r"FAMILIPRIX|PHARMAPRIX|CLIN\. VET|PRAXIS TERRE|CPAP CLINIC|ANTIDOTE",
        re.I,
    )),
    ("Restaurants & cafes", re.compile(
        r"TIM HORTONS|POUTINE|PHO |BOUSTAN|WANG'S|Subway|LA BOLERIE|MR PUFFS|"
        r"L AUROCHS|OLLY FRESCO|RESTAURANT|CAFE|STARBUCKS|IZAKAYA|BEN & FLORENTINE|"
        r"LA TASSE|BK BRICK|CHEZ CHILI|SF-LA RONDE FOOD|HOTEL ",
        re.I,
    )),
    ("Gas & auto", re.compile(
        r"SHELL|ESSO|CIRCLE ?K|COUCHE-TARD|MOBIL@|LES PNEUS|FRANKLIN MOTOS",
        re.I,
    )),
    ("Home & hardware", re.compile(
        r"CANAC|CANADIAN TIRE|DOLLARAMA|RONA |HOME DEPOT|DENEIGEMENT",
        re.I,
    )),
    ("Kids, pets & sports", re.compile(
        r"BMX |FQSC|Bromont|SKYSPA|ANIMALERIE|MONDOU|GRIFFES ET CROCS|"
        r"PAINTBALL|LS SPORTS|VE RIVE-SUD",
        re.I,
    )),
    ("Local shops & bakery", re.compile(r"PASQUIER|AGRIZONE|OSTIGUY", re.I)),
    ("Entertainment & travel", re.compile(
        r"MUSEUM|WAR MUSEUM|REM STATION|A30 EXPRESS|CITY OF OTTAWA|LE CIRCUIT|"
        r"LA RONDE|REGISTRAIRE",
        re.I,
    )),
    ("Parking & fees", re.compile(r"PARKING|LOT \d", re.I)),
    ("Other shopping", re.compile(r"ST JEAN SUR|SQ \*", re.I)),
]

RECURRING_HINTS = re.compile(
    r"SPOTIFY|DROPBOX|GOOGLE \*|Garmin|ROGERS|VIRGIN PLUS|BELL MOBILITY|"
    r"PrimeVideo|Amazon Channels|Workspace|eero",
    re.I,
)


@dataclass(frozen=True)
class Transaction:
    when: date
    description: str
    amount: float  # positive = charge, negative = credit/payment

    @property
    def category(self) -> str:
        for name, pat in CATEGORY_RULES:
            if pat.search(self.description):
                return name
        return "Uncategorized"

    @property
    def is_payment(self) -> bool:
        return self.category == "Payments & credits" or self.amount < 0


def parse_amount(parts: list[str]) -> float:
    raw = ""
    if len(parts) >= 3 and parts[2].strip():
        raw = parts[2].strip()
    elif len(parts) >= 4 and parts[3].strip():
        raw = parts[3].strip()
    if not raw:
        raise ValueError(f"missing amount in row: {parts}")
    return float(raw.replace(",", ""))


def load_transactions(path: Path) -> list[Transaction]:
    txns: list[Transaction] = []
    with path.open(newline="", encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = next(csv.reader([line]))
            when = datetime.strptime(parts[0], "%Y-%m-%d").date()
            desc = parts[1].strip()
            amount = parse_amount(parts)
            txns.append(Transaction(when=when, description=desc, amount=amount))
    txns.sort(key=lambda t: t.when)
    return txns


def expenses(txns: Iterable[Transaction]) -> list[Transaction]:
    return [t for t in txns if not t.is_payment and t.amount > 0]


def fmt_money(v: float) -> str:
    return f"${v:,.2f}"


def month_key(d: date) -> str:
    return d.strftime("%Y-%m")


def build_insights(txns: list[Transaction], spend: list[Transaction]) -> list[str]:
    tips: list[str] = []
    by_cat: dict[str, float] = defaultdict(float)
    for t in spend:
        by_cat[t.category] += t.amount

    total = sum(by_cat.values())
    if total <= 0:
        return ["No card spending found in this file."]

    sorted_cats = sorted(by_cat.items(), key=lambda x: -x[1])
    top = sorted_cats[0]
    tips.append(
        f"<b>Biggest category:</b> {top[0]} at {fmt_money(top[1])} "
        f"({100 * top[1] / total:.0f}% of spending). Focus here first."
    )

    # Amazon
    amazon = by_cat.get("Amazon & online retail", 0)
    if amazon > 200:
        amazon_tx = [t for t in spend if t.category == "Amazon & online retail"]
        tips.append(
            f"<b>Amazon:</b> {fmt_money(amazon)} across {len(amazon_tx)} purchases "
            f"(avg {fmt_money(amazon / len(amazon_tx))}). Try a 48-hour cart rule, "
            f"one monthly order day, and unsubscribe from marketing emails."
        )

    # Subscriptions
    subs = [t for t in spend if t.category == "Subscriptions & telecom"]
    if subs:
        sub_total = sum(t.amount for t in subs)
        merchants: dict[str, float] = defaultdict(float)
        for t in subs:
            key = t.description.split(",")[0][:40]
            merchants[key] += t.amount
        recurring = sorted(merchants.items(), key=lambda x: -x[1])[:6]
        lines = ", ".join(f"{k} ({fmt_money(v)})" for k, v in recurring)
        tips.append(
            f"<b>Recurring charges:</b> {fmt_money(sub_total)} total. Review: {lines}. "
            f"Cancel duplicates (e.g. Dropbox + Google storage) and shop phone plans annually."
        )

    # Groceries + Costco
    grocery = by_cat.get("Groceries", 0)
    if grocery > 500:
        costco = sum(
            t.amount for t in spend
            if "COSTCO" in t.description.upper()
        )
        tips.append(
            f"<b>Groceries:</b> {fmt_money(grocery)} including Costco {fmt_money(costco)}. "
            f"Plan meals around flyers (Maxi/Super C), batch Costco runs, and avoid "
            f"extra small-shop trips that add up."
        )

    # Restaurants
    rest = by_cat.get("Restaurants & cafes", 0)
    if rest > 150:
        tips.append(
            f"<b>Dining out:</b> {fmt_money(rest)}. Even one fewer takeout trip per week "
            f"can save $50–100/month for the family."
        )

    # Gas & auto
    gas = by_cat.get("Gas & auto", 0)
    if gas > 200:
        tips.append(
            f"<b>Fuel & auto:</b> {fmt_money(gas)}. Use a price app (e.g. GasBuddy), combine errands, "
            f"and compare tire/service quotes before large shop visits."
        )

    # Home & hardware (RONA, Canac, Home Depot)
    home_hw = by_cat.get("Home & hardware", 0)
    if home_hw > 800:
        tips.append(
            f"<b>Home & hardware:</b> {fmt_money(home_hw)} (Canac, RONA, Home Depot, etc.). "
            f"Batch renovation purchases and wait for seasonal sales."
        )

    unc = by_cat.get("Uncategorized", 0)
    if unc > 500:
        tips.append(
            f"<b>Miscellaneous:</b> {fmt_money(unc)} not auto-categorized — skim the merchant "
            f"list in this report and tag one-off vs recurring items as a family."
        )

    # Monthly trend
    by_month: dict[str, float] = defaultdict(float)
    for t in spend:
        by_month[month_key(t.when)] += t.amount
    months = sorted(by_month.items())
    if len(months) >= 3:
        recent = months[-3:]
        older = months[:-3]
        avg_recent = sum(v for _, v in recent) / len(recent)
        avg_older = sum(v for _, v in older) / len(older) if older else avg_recent
        if avg_recent > avg_older * 1.1:
            tips.append(
                f"<b>Trend:</b> Last 3 months average {fmt_money(avg_recent)}/mo vs "
                f"earlier {fmt_money(avg_older)}/mo — spending is rising; set a "
                f"family monthly card budget of {fmt_money(avg_older * 0.95)}."
            )
        elif avg_recent < avg_older * 0.9:
            tips.append(
                f"<b>Trend:</b> Recent months are lower ({fmt_money(avg_recent)}/mo) — "
                f"keep current habits and redirect savings to emergency fund."
            )

    tips.append(
        "<b>Family action plan:</b> (1) List all subscriptions and cut one this week. "
        "(2) Agree on a weekly grocery budget. (3) Pause Amazon unless planned. "
        "(4) Pay card in full to avoid interest. (5) Review this report together monthly."
    )
    return tips


def make_pdf(
    out_path: Path,
    txns: list[Transaction],
    spend: list[Transaction],
) -> None:
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        "Title",
        parent=styles["Heading1"],
        fontSize=20,
        alignment=TA_CENTER,
        spaceAfter=12,
    )
    h2 = ParagraphStyle("H2", parent=styles["Heading2"], fontSize=14, spaceBefore=14, spaceAfter=6)
    body = ParagraphStyle("Body", parent=styles["Normal"], fontSize=10, leading=14)
    small = ParagraphStyle("Small", parent=styles["Normal"], fontSize=8, textColor=colors.grey)

    start = min(t.when for t in txns)
    end = max(t.when for t in txns)
    total_spend = sum(t.amount for t in spend)
    payments = sum(t.amount for t in txns if t.is_payment)

    by_cat: dict[str, float] = defaultdict(float)
    for t in spend:
        by_cat[t.category] += t.amount
    cat_rows = [["Category", "Amount", "% of spend"]]
    for cat, amt in sorted(by_cat.items(), key=lambda x: -x[1]):
        cat_rows.append([cat, fmt_money(amt), f"{100 * amt / total_spend:.1f}%"])

    by_month: dict[str, float] = defaultdict(float)
    for t in spend:
        by_month[month_key(t.when)] += t.amount
    month_rows = [["Month", "Spending"]]
    for m, amt in sorted(by_month.items()):
        month_rows.append([m, fmt_money(amt)])

    # Top merchants
    merchants: dict[str, float] = defaultdict(float)
    for t in spend:
        label = t.description.split(",")[0].strip()[:45]
        merchants[label] += t.amount
    top_m = [["Merchant", "Total"]]
    for name, amt in sorted(merchants.items(), key=lambda x: -x[1])[:15]:
        top_m.append([name, fmt_money(amt)])

    table_style = TableStyle(
        [
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1a5276")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.whitesmoke),
            ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
            ("FONTSIZE", (0, 0), (-1, -1), 9),
            ("ALIGN", (1, 1), (-1, -1), "RIGHT"),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.lightgrey),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#ebf5fb")]),
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]
    )

    doc = SimpleDocTemplate(
        str(out_path),
        pagesize=letter,
        rightMargin=0.6 * inch,
        leftMargin=0.6 * inch,
        topMargin=0.6 * inch,
        bottomMargin=0.6 * inch,
    )
    story: list = []

    story.append(Paragraph("CIBC Family Spending & Savings Report", title_style))
    story.append(
        Paragraph(
            f"Period: {start.isoformat()} to {end.isoformat()} · "
            f"{len(spend)} purchases · Generated {datetime.now().strftime('%Y-%m-%d')}",
            ParagraphStyle("sub", parent=body, alignment=TA_CENTER),
        )
    )
    story.append(Spacer(1, 0.2 * inch))

    summary_data = [
        ["Total card spending (excl. payments)", fmt_money(total_spend)],
        ["Payments/credits applied", fmt_money(payments)],
        ["Average monthly spending", fmt_money(total_spend / max(len(by_month), 1))],
        ["Transactions analyzed", str(len(txns))],
    ]
    st = Table(summary_data, colWidths=[3.5 * inch, 2 * inch])
    st.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#eafaf1")),
                ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
                ("FONTSIZE", (0, 0), (-1, -1), 10),
                ("ALIGN", (1, 0), (1, -1), "RIGHT"),
                ("BOX", (0, 0), (-1, -1), 1, colors.HexColor("#27ae60")),
                ("INNERGRID", (0, 0), (-1, -1), 0.25, colors.white),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ]
        )
    )
    story.append(st)
    story.append(Spacer(1, 0.15 * inch))

    story.append(Paragraph("Spending by category", h2))
    ct = Table(cat_rows, colWidths=[3.2 * inch, 1.2 * inch, 1 * inch])
    ct.setStyle(table_style)
    story.append(ct)

    story.append(Paragraph("Monthly spending", h2))
    mt = Table(month_rows, colWidths=[2 * inch, 1.5 * inch])
    mt.setStyle(table_style)
    story.append(mt)

    story.append(PageBreak())
    story.append(Paragraph("Top merchants", h2))
    tt = Table(top_m, colWidths=[4 * inch, 1.5 * inch])
    tt.setStyle(table_style)
    story.append(tt)

    story.append(Paragraph("Savings opportunities for your family", h2))
    for tip in build_insights(txns, spend):
        story.append(Paragraph(f"• {tip}", body))
        story.append(Spacer(1, 0.08 * inch))

    story.append(Spacer(1, 0.2 * inch))
    story.append(
        Paragraph(
            "Note: Categories are inferred from merchant names; verify unusual items manually. "
            "This report is for household planning only, not tax advice.",
            small,
        )
    )

    doc.build(story)


def main() -> None:
    import sys

    csv_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads" / "cibc (1).csv"
    out_path = (
        Path(sys.argv[2])
        if len(sys.argv) > 2
        else csv_path.parent / "cibc-family-savings-report.pdf"
    )

    txns = load_transactions(csv_path)
    spend = expenses(txns)
    make_pdf(out_path, txns, spend)
    total = sum(t.amount for t in spend)
    print(f"Parsed {len(txns)} rows, {len(spend)} expenses, total spend {fmt_money(total)}")
    print(f"Report written to: {out_path}")


if __name__ == "__main__":
    main()
