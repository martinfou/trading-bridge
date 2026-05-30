package com.martinfou.trading.backtest.report;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class BacktestReportGenerator {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(NY);
    private static final DateTimeFormatter DTF_SHORT = DateTimeFormatter.ofPattern("MMM dd").withZone(NY);
    private static final DateTimeFormatter TSF = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(NY);

    private static final int CHART_W = 500;
    private static final int CHART_H = 280;
    private static final Color COLOR_UP = new Color(39, 174, 96);
    private static final Color COLOR_DOWN = new Color(231, 76, 60);
    private static final Color COLOR_BG = new Color(248, 249, 250);
    private static final Color COLOR_GRID = new Color(220, 220, 220);
    private static final Color COLOR_EQUITY = new Color(41, 128, 185);
    private static final Color COLOR_DD = new Color(231, 76, 60, 180);

    private final BacktestResult result;
    private final String asset;
    private final String label;
    private final Path outDir;
    private MonteCarloSimulation.Result monteCarlo;

    public BacktestReportGenerator(BacktestResult r, String asset, String label, Path outDir) {
        this.result = r; this.asset = asset; this.label = label; this.outDir = outDir;
    }

    /** Optionally attaches Monte Carlo simulation results for risk analysis. */
    public BacktestReportGenerator withMonteCarlo(MonteCarloSimulation.Result mc) {
        this.monteCarlo = mc;
        return this;
    }

    // ───────────────────────────────────────────────────────────
    //  PUBLIC ENTRY POINT
    // ───────────────────────────────────────────────────────────

    public Path generate() throws Exception {
        String fn = label.replaceAll("[^a-zA-Z0-9]", "_") + "_" + asset.replace("/", "") + "_"
            + java.time.LocalDate.now() + ".pdf";
        Path path = outDir.resolve(fn);

        Font titleF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Font subF   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        Font hdrF   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font normF  = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font smallF = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Color blue  = new Color(41, 128, 185);
        Color gray  = new Color(236, 240, 241);
        Color white = Color.WHITE;
        Color greenBg = new Color(212, 239, 223);
        Color winText = new Color(39, 174, 96);
        Color lossText = new Color(231, 76, 60);

        Document doc = new Document(PageSize.A4);
        PdfWriter.getInstance(doc, new FileOutputStream(path.toFile()));
        doc.open();

        // ── Header ──
        Paragraph title = new Paragraph("Backtest Report: " + label + " | " + asset, titleF);
        title.setAlignment(Element.ALIGN_CENTER); title.setSpacingAfter(6);
        doc.add(title);

        Paragraph info = new Paragraph(
            "Period: " + DTF.format(result.periodStart()) + " → " + DTF.format(result.periodEnd())
            + "  |  Capital: $" + String.format("%,.0f", result.initialCapital())
            + "  |  Commission: $" + fmt2(result.totalCommission()), smallF);
        info.setAlignment(Element.ALIGN_CENTER); info.setSpacingAfter(14);
        doc.add(info);

        // ── KPI Strip ──
        PdfPTable strip = new PdfPTable(6);
        strip.setWidthPercentage(100); strip.setSpacingAfter(12);
        String[][] kpis = {
            {"Total Return", fmtPct(result.totalReturnPct())},
            {"Sharpe Ratio",  fmt2(result.sharpeRatio())},
            {"Profit Factor", fmt2(result.profitFactor())},
            {"Win Rate",      fmtPct(result.winRatePct())},
            {"Max DD",        fmtPct(result.maxDrawdownPct())},
            {"Total Trades",  String.valueOf(result.totalTrades())},
        };
        for (String[] kv : kpis) {
            PdfPCell c = new PdfPCell();
            c.setBackgroundColor(blue); c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setPadding(4);
            c.addElement(new Paragraph(kv[0], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, white)));
            c.addElement(new Paragraph(kv[1], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, white)));
            strip.addCell(c);
        }
        doc.add(strip);

        // ── Details Grid (2-col) ──
        PdfPTable det = new PdfPTable(4);
        det.setWidthPercentage(100); det.setSpacingAfter(14);
        row(det, "Initial Capital",  "$" + fmt2(result.initialCapital()), hdrF, normF, gray);
        row(det, "Final Equity",     "$" + fmt2(result.finalEquity()), hdrF, normF, gray);
        row(det, "Net Profit",       "$" + fmt2(result.totalPnl()), hdrF, normF, gray);
        row(det, "Avg Trade",        "$" + fmt2(result.avgTradePnl()), hdrF, normF, gray);
        row(det, "Sortino",          fmt2(result.sortinoRatio()), hdrF, normF, gray);
        row(det, "Calmar",           fmt2(result.calmarRatio()), hdrF, normF, gray);
        row(det, "Wins / Losses",    result.winningTrades() + " / " + result.losingTrades(), hdrF, normF, gray);
        row(det, "Commission",       "$" + fmt2(result.totalCommission()), hdrF, normF, gray);
        doc.add(det);

        // ── Trade Sub-stats ──
        List<Trade> trades = result.trades();
        if (trades != null && !trades.isEmpty()) {
            double avgW = trades.stream().filter(t -> t.pnl() > 0).mapToDouble(Trade::pnl).average().orElse(0);
            double avgL = trades.stream().filter(t -> t.pnl() < 0).mapToDouble(Trade::pnl).average().orElse(0);
            double maxW = trades.stream().mapToDouble(Trade::pnl).max().orElse(0);
            double maxL = trades.stream().mapToDouble(Trade::pnl).min().orElse(0);
            double avgBars = trades.stream().mapToDouble(t ->
                Duration.between(t.entryTime(), t.exitTime()).toHours()).average().orElse(0);
            double exp = (result.winRatePct()/100*avgW) - ((1-result.winRatePct()/100)*Math.abs(avgL));
            double wr = result.winRatePct()/100.0;
            double rMul = avgW / Math.max(Math.abs(avgL), 0.01);
            double kelly = (wr * rMul - (1-wr)) / rMul * 100;

            PdfPTable st = new PdfPTable(4); st.setWidthPercentage(100); st.setSpacingAfter(14);
            row(st, "Avg Win",       "$" + fmt2(avgW), hdrF, normF, gray);
            row(st, "Avg Loss",      "$" + fmt2(avgL), hdrF, normF, gray);
            row(st, "Max Win",       "$" + fmt0(maxW), hdrF, normF, gray);
            row(st, "Max Loss",      "$" + fmt0(Math.abs(maxL)), hdrF, normF, gray);
            row(st, "Expectancy",    "$" + fmt2(exp), hdrF, normF, gray);
            row(st, "Avg Duration",  fmt1(avgBars) + "h", hdrF, normF, gray);
            row(st, "W/L Ratio",     fmt2(avgW / Math.max(Math.abs(avgL), 0.01)), hdrF, normF, gray);
            row(st, "Kelly %",       fmt1(Math.max(kelly, 0)) + "%", hdrF, normF, gray);
            doc.add(st);
        }

        // ── Equity + Drawdown Chart ──
        doc.add(new Paragraph("Equity & Drawdown", subF));
        doc.add(new Paragraph(" "));
        if (result.equityCurve() != null && result.equityCurve().size() > 1) {
            Path chartPng = drawChart(result.equityCurve(), result.periodStart(), result.periodEnd());
            Image img = Image.getInstance(chartPng.toAbsolutePath().toString());
            img.scaleToFit(PageSize.A4.getWidth() - 72, 300);
            img.setAlignment(Element.ALIGN_CENTER);
            img.setSpacingAfter(16);
            doc.add(img);
            Files.deleteIfExists(chartPng);
        }

        // ── Monte Carlo Section ──
        if (monteCarlo != null) {
            doc.add(new Paragraph("Monte Carlo Simulation (" + monteCarlo.totalRuns() + " runs)", subF));
            doc.add(new Paragraph(" "));
            PdfPTable mcStrip = new PdfPTable(6);
            mcStrip.setWidthPercentage(100); mcStrip.setSpacingAfter(14);
            Color mcBlue = new Color(142, 68, 173);
            String[][] mcKpis = {
                {"Median P&L",          "$" + fmt2(monteCarlo.medianPnl())},
                {"VaR 95%",             "$" + fmt2(monteCarlo.var95())},
                {"Loss Prob.",           fmt1(monteCarlo.probabilityOfLoss()) + "%"},
                {"Median DD",           fmt2(monteCarlo.medianDrawdown()) + "%"},
                {"DD 95th Pctile",      fmt2(monteCarlo.drawdown95()) + "%"},
                {"Median Sharpe",       fmt2(monteCarlo.medianSharpe())},
            };
            for (String[] kv : mcKpis) {
                PdfPCell c = new PdfPCell();
                c.setBackgroundColor(mcBlue); c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setPadding(4);
                boolean negative = kv[0].contains("Loss") || kv[0].contains("VaR") || kv[0].contains("DD");
                Color mcValColor = negative ? new Color(255, 150, 150) : white;
                c.addElement(new Paragraph(kv[0], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, white)));
                c.addElement(new Paragraph(kv[1], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, mcValColor)));
                mcStrip.addCell(c);
            }
            doc.add(mcStrip);
        }

        // ── Monthly Returns ──
        doc.add(new Paragraph("Monthly Returns", subF));
        doc.add(new Paragraph(" "));
        if (trades != null && !trades.isEmpty()) {
            doc.add(drawMonthlyTable(trades));
        }

        // ── Trade List ──
        doc.add(new Paragraph("Trade List", subF));
        doc.add(new Paragraph(" "));
        if (trades != null && !trades.isEmpty()) {
            PdfPTable tt = new PdfPTable(8);
            tt.setWidthPercentage(100);
            tt.setWidths(new float[]{4, 10, 6, 8, 8, 8, 6, 6});
            String[] th = {"#", "Symbol", "Dir", "Entry", "Exit", "P&L", "P&L%", "Duration"};
            for (String h : th) {
                PdfPCell c = new PdfPCell(new Phrase(h, hdrF));
                c.setBackgroundColor(blue); c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setPadding(3); c.setBorderWidth(0.5f);
                tt.addCell(c);
            }
            for (int i = 0; i < trades.size(); i++) {
                Trade t = trades.get(i);
                boolean win = t.pnl() >= 0;
                Color bg = win ? greenBg : new Color(252, 222, 222);
                Color txt = win ? winText : lossText;
                String dir = t.side() == Order.Side.BUY ? "LONG" : "SHORT";
                double pnlPct = t.pnlPercent();
                long hours = Duration.between(t.entryTime(), t.exitTime()).toHours();
                String[] vals = {
                    String.valueOf(i + 1),
                    t.symbol(),
                    dir,
                    fmtPrice(t.entryPrice()),
                    fmtPrice(t.exitPrice()),
                    (win ? "+" : "") + fmt2(t.pnl()),
                    (win ? "+" : "") + fmt2(pnlPct) + "%",
                    hours < 24 ? hours + "h" : (hours / 24) + "d " + (hours % 24) + "h"
                };
                for (int j = 0; j < vals.length; j++) {
                    Font f = (j == 5 || j == 6) ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, txt) : normF;
                    PdfPCell c = new PdfPCell(new Phrase(vals[j], f));
                    c.setBackgroundColor(bg); c.setHorizontalAlignment(Element.ALIGN_CENTER);
                    c.setPadding(2); c.setBorderWidth(0.3f);
                    tt.addCell(c);
                }
            }
            doc.add(tt);
        }

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("Generated by Trading Bridge | "
            + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), smallF));

        doc.close();
        System.out.println("PDF: " + path.toAbsolutePath());
        return path;
    }

    // ───────────────────────────────────────────────────────────
    //  CHART — Two panels: Equity (top 70%) + Drawdown (bottom 30%)
    // ───────────────────────────────────────────────────────────

    private Path drawChart(List<Double> equityCurve, Instant periodStart, Instant periodEnd) throws Exception {
        Path png = Files.createTempFile("eqchart_", ".png");

        int w = CHART_W, h = CHART_H;
        int marginL = 60, marginR = 20, marginT = 15, marginB = 35, gap = 5;
        int plotW = w - marginL - marginR;
        int upperH = (int)((h - marginT - marginB - gap) * 0.70);
        int lowerH = (h - marginT - marginB - gap) - upperH;
        int eqTop = marginT;
        int ddTop = eqTop + upperH + gap;

        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(COLOR_BG);
        g.fillRect(0, 0, w, h);

        int n = equityCurve.size();
        double eqMin = equityCurve.stream().mapToDouble(d -> d).min().orElse(0);
        double eqMax = equityCurve.stream().mapToDouble(d -> d).max().orElse(1);
        double eqPadding = (eqMax - eqMin) * 0.05;
        if (eqPadding == 0) eqPadding = eqMin * 0.01;
        eqMin -= eqPadding;
        eqMax += eqPadding;
        double eqRange = eqMax - eqMin;

        // Compute drawdown
        double runningMax = equityCurve.get(0);
        List<Double> dd = new ArrayList<>(n);
        double ddMin = 0;
        for (double eq : equityCurve) {
            if (eq > runningMax) runningMax = eq;
            double ddp = runningMax > 0 ? (eq - runningMax) / runningMax * 100 : 0;
            dd.add(ddp);
            if (ddp < ddMin) ddMin = ddp;
        }
        // Add 20% padding below lowest DD
        double ddPad = Math.abs(ddMin) * 0.2;
        double ddLo = ddMin - ddPad;
        double ddHi = 0;

        int lastIdx = Math.max(n - 1, 1);

        // ════════════════════════════════════════════════════════
        //  UPPER PANEL — Equity Curve
        // ════════════════════════════════════════════════════════

        // Horizontal grid lines
        g.setColor(COLOR_GRID);
        for (int row = 0; row <= 4; row++) {
            int y = eqTop + upperH * row / 4;
            g.drawLine(marginL, y, w - marginR, y);
        }

        // Y-axis labels (equity $ values)
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 9));
        g.setColor(Color.DARK_GRAY);
        for (int row = 0; row <= 4; row++) {
            double frac = (4.0 - row) / 4.0;
            double v = eqMin + frac * eqRange;
            int y = eqTop + upperH * row / 4;
            String lbl = "$" + (v >= 1000 ? Math.round(v) : fmt2(v));
            int tw = g.getFontMetrics().stringWidth(lbl);
            g.drawString(lbl, marginL - tw - 4, y + 3);
        }

        // Panel title
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 10));
        g.setColor(new Color(41, 128, 185));
        g.drawString("Equity", 4, eqTop + 12);

        // Equity line
        g.setColor(new Color(41, 128, 185));
        g.setStroke(new BasicStroke(1.5f));
        Path2D eqPath = new Path2D.Double();
        for (int i = 0; i < n; i++) {
            double x = marginL + ((double) i / lastIdx) * plotW;
            double y = eqTop + upperH - scale(equityCurve.get(i), eqMin, eqMax, upperH);
            if (i == 0) eqPath.moveTo(x, y); else eqPath.lineTo(x, y);
        }
        g.draw(eqPath);

        // ════════════════════════════════════════════════════════
        //  SEPARATOR LINE
        // ════════════════════════════════════════════════════════
        g.setStroke(new BasicStroke(1));
        g.setColor(new Color(180, 180, 180));
        g.drawLine(marginL, ddTop - 1, w - marginR, ddTop - 1);

        // ════════════════════════════════════════════════════════
        //  LOWER PANEL — Drawdown
        // ════════════════════════════════════════════════════════

        // Grid
        g.setColor(COLOR_GRID);
        for (int row = 0; row <= 2; row++) {
            int y = ddTop + lowerH * row / 2;
            g.drawLine(marginL, y, w - marginR, y);
        }

        // Y-axis labels (DD %)
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 9));
        g.setColor(Color.DARK_GRAY);
        // Only show 0% (top) and ddLo% (bottom)
        g.drawString("0%", marginL - 24, ddTop + 3);
        g.drawString(fmt1(ddLo) + "%", marginL - 30, ddTop + lowerH + 3);

        // Panel title
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 10));
        g.setColor(new Color(231, 76, 60));
        g.drawString("Drawdown", 4, ddTop + 12);

        // Drawdown filled area
        if (n > 1) {
            double y0 = ddTop + lowerH - scale(0, ddLo, ddHi, lowerH);
            Path2D ddPath = new Path2D.Double();
            ddPath.moveTo(marginL, y0);
            for (int i = 0; i < n; i++) {
                double x = marginL + ((double) i / lastIdx) * plotW;
                double y = ddTop + lowerH - scale(dd.get(i), ddLo, ddHi, lowerH);
                ddPath.lineTo(x, y);
            }
            ddPath.lineTo(marginL + plotW, y0);
            ddPath.closePath();
            g.setColor(new Color(231, 76, 60, 160));
            g.fill(ddPath);

            // Outline
            g.setColor(new Color(192, 57, 43));
            g.setStroke(new BasicStroke(1));
            Path2D ddLine = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                double x = marginL + ((double) i / lastIdx) * plotW;
                double y = ddTop + lowerH - scale(dd.get(i), ddLo, ddHi, lowerH);
                if (i == 0) ddLine.moveTo(x, y); else ddLine.lineTo(x, y);
            }
            g.draw(ddLine);
        }

        // ════════════════════════════════════════════════════════
        //  X-AXIS LABELS — dates from periodStart/periodEnd
        // ════════════════════════════════════════════════════════
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 8));
        g.setColor(Color.DARK_GRAY);
        long periodMs = periodEnd != null && periodStart != null
            ? periodEnd.toEpochMilli() - periodStart.toEpochMilli() : 0;
        for (int col = 0; col <= 4; col++) {
            int idx = Math.min(col * (n - 1) / 4, n - 1);
            double x = marginL + ((double) idx / lastIdx) * plotW;
            String label;
            if (periodMs > 0 && periodStart != null) {
                long ts = periodStart.toEpochMilli() + periodMs * idx / (n - 1);
                label = DTF_SHORT.format(java.time.Instant.ofEpochMilli(ts));
            } else {
                label = "Bar " + idx;
            }
            int tw = g.getFontMetrics().stringWidth(label);
            g.drawString(label, (int) x - tw / 2, ddTop + lowerH + 18);
        }

        g.dispose();
        ImageIO.write(bi, "PNG", png.toFile());
        return png;
    }

    // ───────────────────────────────────────────────────────────
    //  MONTHLY RETURNS TABLE
    // ───────────────────────────────────────────────────────────

    private PdfPTable drawMonthlyTable(List<Trade> trades) {
        String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
        double[] monthly = new double[12];
        int[] monthlyCount = new int[12];
        for (Trade t : trades) {
            int mo = t.exitTime().atZone(NY).getMonthValue() - 1;
            monthly[mo] += t.pnl();
            monthlyCount[mo]++;
        }

        PdfPTable mt = new PdfPTable(4);
        mt.setWidthPercentage(100); mt.setSpacingAfter(14);
        Font mh = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font mn = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Color hdrBg = new Color(52, 73, 94);
        Color altBg = new Color(245, 245, 245);

        String[] heads = {"Month", "Net P&L", "Trades", "Avg/Trade"};
        for (String h : heads) {
            PdfPCell c = new PdfPCell(new Phrase(h, mh));
            c.setBackgroundColor(hdrBg); c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setPadding(3);
            c.getPhrase().getFont().setColor(Color.WHITE);
            mt.addCell(c);
        }
        for (int i = 0; i < 12; i++) {
            boolean has = monthlyCount[i] > 0;
            Color bg = has ? (monthly[i] >= 0 ? new Color(212, 239, 223) : new Color(252, 222, 222)) : altBg;
            cell(mt, months[i], mn, bg);
            cell(mt, has ? "$" + fmt2(monthly[i]) : "-", mn, bg);
            cell(mt, has ? String.valueOf(monthlyCount[i]) : "-", mn, bg);
            cell(mt, has ? "$" + fmt2(monthly[i] / monthlyCount[i]) : "-", mn, bg);
        }
        return mt;
    }

    // ───────────────────────────────────────────────────────────
    //  HELPERS
    // ───────────────────────────────────────────────────────────

    private void row(PdfPTable t, String l, String v, Font h, Font n, Color bg) {
        PdfPCell lc = new PdfPCell(new Phrase(l, h)); lc.setBackgroundColor(bg); t.addCell(lc);
        PdfPCell vc = new PdfPCell(new Phrase(v, n));
        vc.setHorizontalAlignment(Element.ALIGN_RIGHT); t.addCell(vc);
    }

    private void cell(PdfPTable t, String v, Font f, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(v, f));
        c.setHorizontalAlignment(Element.ALIGN_CENTER); c.setBackgroundColor(bg);
        c.setPadding(2);
        t.addCell(c);
    }

    private static double scale(double v, double lo, double hi, double px) {
        return (v - lo) / (hi - lo) * px;
    }

    private static double[] scaleLinear(double lo, double hi) {
        double range = hi - lo;
        double nice = Math.pow(10, Math.floor(Math.log10(range)));
        if (range / nice < 2) nice /= 2;
        else if (range / nice > 5) nice *= 2;
        return new double[]{lo, hi};
    }

    private String fmtPct(double v) { return String.format("%.2f%%", v); }
    private String fmt2(double v)  { return String.format("%.2f", v); }
    private String fmt1(double v)  { return String.format("%.1f", v); }
    private String fmt0(double v)  { return String.format("%.0f", v); }
    private String fmtPrice(double v) { return v >= 100 ? String.format("%.2f", v) : String.format("%.5f", v); }
}
