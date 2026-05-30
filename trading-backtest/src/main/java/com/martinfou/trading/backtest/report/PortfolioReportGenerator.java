package com.martinfou.trading.backtest.report;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.CorrelationMatrix;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.backtest.PerformanceMetrics;
import com.martinfou.trading.backtest.PortfolioBuilder;
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
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates a combined PDF report for a portfolio of strategies tested on
 * the same asset. Shows equity overlay, correlation matrix, drawdown overlap,
 * portfolio allocations, and per-strategy comparison.
 *
 * <p>Each strategy gets its own {@link BacktestResult} and optional
 * {@link MonteCarloSimulation.Result}. The "combined" equity curve
 * is the equal-weight average of all strategy equity curves.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * PortfolioReportGenerator gen = new PortfolioReportGenerator(
 *     results, mcResults, asset, "Weekly Portfolio", outDir
 * );
 * gen.withCorrelationMatrix(cm)
 *    .withPortfolioAllocation(equalWeight, maxSharpe)
 *    .generate();
 * }</pre>
 */
public class PortfolioReportGenerator {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DTF_SHORT = DateTimeFormatter.ofPattern("MMM dd").withZone(NY);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(NY);

    private static final int CHART_W = 500;
    private static final int CHART_H = 320;
    private static final Color COLOR_BG = new Color(248, 249, 250);
    private static final Color COLOR_GRID = new Color(220, 220, 220);
    private static final Color[] STRAT_COLORS = {
        new Color(41, 128, 185),   // blue
        new Color(231, 76, 60),    // red
        new Color(39, 174, 96),    // green
        new Color(142, 68, 173),   // purple
        new Color(243, 156, 18),   // orange
        new Color(52, 73, 94),     // dark gray
    };

    private final Map<String, BacktestResult> results;
    private final Map<String, MonteCarloSimulation.Result> mcResults;
    private final String asset;
    private final String label;
    private final Path outDir;

    private CorrelationMatrix correlationMatrix;
    private PortfolioBuilder.Portfolio equalWeightPortfolio;
    private PortfolioBuilder.Portfolio maxSharpePortfolio;

    // ───────────────────────────────────────────────────────────
    //  Constructor
    // ───────────────────────────────────────────────────────────

    /**
     * @param results  map of strategy name → backtest result (all on the same asset)
     * @param mcResults map of strategy name → Monte Carlo result (nullable)
     * @param asset    the traded pair / asset
     * @param label    report label (e.g. "Weekly Portfolio")
     * @param outDir   output directory for the PDF
     */
    public PortfolioReportGenerator(
            Map<String, BacktestResult> results,
            Map<String, MonteCarloSimulation.Result> mcResults,
            String asset, String label, Path outDir) {
        this.results = Map.copyOf(results);
        this.mcResults = mcResults != null ? Map.copyOf(mcResults) : Map.of();
        this.asset = asset;
        this.label = label;
        this.outDir = outDir;
    }

    /** Attach correlation matrix for the portfolio. */
    public PortfolioReportGenerator withCorrelationMatrix(CorrelationMatrix cm) {
        this.correlationMatrix = cm;
        return this;
    }

    /** Attach portfolio allocations from PortfolioBuilder. */
    public PortfolioReportGenerator withPortfolioAllocation(
            PortfolioBuilder.Portfolio equalWeight,
            PortfolioBuilder.Portfolio maxSharpe) {
        this.equalWeightPortfolio = equalWeight;
        this.maxSharpePortfolio = maxSharpe;
        return this;
    }

    // ───────────────────────────────────────────────────────────
    //  PUBLIC ENTRY POINT
    // ───────────────────────────────────────────────────────────

    public Path generate() throws Exception {
        List<String> names = results.keySet().stream().sorted().toList();
        if (names.isEmpty()) throw new IllegalArgumentException("No results to report");
        if (names.size() < 2) {
            // Fall back to individual report
            String name = names.getFirst();
            BacktestResult r = results.get(name);
            MonteCarloSimulation.Result mc = mcResults.get(name);
            String fn = "portfolio_" + label.replaceAll("[^a-zA-Z0-9]", "_")
                + "_" + asset.replace("/", "") + "_" + java.time.LocalDate.now() + ".pdf";
            Path path = outDir.resolve(fn);
            // Simple PDF — single strategy
            BacktestReportGenerator gen = new BacktestReportGenerator(r, asset, label, outDir);
            if (mc != null) gen.withMonteCarlo(mc);
            return gen.generate();
        }

        String fn = "portfolio_" + label.replaceAll("[^a-zA-Z0-9]", "_")
            + "_" + asset.replace("/", "") + "_" + java.time.LocalDate.now() + ".pdf";
        Path path = outDir.resolve(fn);

        Font titleF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Font subF   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        Font hdrF   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font normF  = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font smallF = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Color blue  = new Color(41, 128, 185);
        Color gray  = new Color(236, 240, 241);
        Color white = Color.WHITE;

        Document doc = new Document(PageSize.A4);
        PdfWriter.getInstance(doc, new FileOutputStream(path.toFile()));
        doc.open();

        // ── Header ──
        Paragraph title = new Paragraph("Portfolio Report: " + label + " | " + asset, titleF);
        title.setAlignment(Element.ALIGN_CENTER); title.setSpacingAfter(6);
        doc.add(title);

        // Compute combined metrics
        List<Double> combinedEquity = computeCombinedEquity(results);
        double combinedReturn = combinedEquity.size() > 1
            ? (combinedEquity.getLast() - combinedEquity.getFirst()) / combinedEquity.getFirst() * 100
            : 0;
        double combinedSharpe = computeCombinedSharpe(combinedEquity);
        double combinedMaxDD = computeMaxDrawdown(combinedEquity);
        double avgCorrelation = computeAvgCorrelation();
        double divRatio = computeDiversificationRatio();

        // ── KPI Strip ──
        PdfPTable strip = new PdfPTable(6);
        strip.setWidthPercentage(100); strip.setSpacingAfter(12);
        String[][] kpis = {
            {"Combined Return",  fmtPct(combinedReturn)},
            {"Combined Sharpe",  fmt2(combinedSharpe)},
            {"Combined Max DD",  fmtPct(combinedMaxDD)},
            {"Diversification",  fmt2(divRatio) + "x"},
            {"Avg Correlation",  fmt2(avgCorrelation)},
            {"Strategies",       String.valueOf(results.size())},
        };
        Color portfolioBlue = new Color(142, 68, 173); // purple for portfolio
        for (String[] kv : kpis) {
            PdfPCell c = new PdfPCell();
            c.setBackgroundColor(portfolioBlue); c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setPadding(4);
            c.addElement(new Paragraph(kv[0], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, white)));
            c.addElement(new Paragraph(kv[1], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, white)));
            strip.addCell(c);
        }
        doc.add(strip);

        // ── Details ──
        PdfPTable det = new PdfPTable(4);
        det.setWidthPercentage(100); det.setSpacingAfter(14);
        Instant pStart = results.values().stream().map(BacktestResult::periodStart)
            .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant pEnd = results.values().stream().map(BacktestResult::periodEnd)
            .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        String periodStr = pStart != null && pEnd != null
            ? DTF.format(pStart) + " → " + DTF.format(pEnd) : "—";
        row(det, "Period",          periodStr, hdrF, normF, gray);
        row(det, "Capital per strat", "$" + fmt0(firstResult().initialCapital()), hdrF, normF, gray);
        row(det, "Commission",      "$" + fmt2(firstResult().totalCommission()), hdrF, normF, gray);
        row(det, "Pairs tested",    asset, hdrF, normF, gray);
        doc.add(det);

        // ── Equity Overlay Chart ──
        doc.add(new Paragraph("Equity Overlay", subF));
        doc.add(new Paragraph(" "));
        Path chartPng = drawOverlayChart(results, combinedEquity);
        Image img = Image.getInstance(chartPng.toAbsolutePath().toString());
        img.scaleToFit(PageSize.A4.getWidth() - 72, 340);
        img.setAlignment(Element.ALIGN_CENTER);
        img.setSpacingAfter(16);
        doc.add(img);
        Files.deleteIfExists(chartPng);

        // ── Correlation Matrix ──
        if (correlationMatrix != null) {
            doc.add(new Paragraph("Correlation Matrix (P&L)", subF));
            doc.add(new Paragraph(" "));
            List<CorrelationMatrix.Pair> pairs = correlationMatrix.computePnLCorrelation();
            PdfPTable cmTable = new PdfPTable(names.size() + 1);
            cmTable.setWidthPercentage(100);
            cmTable.addCell(headerCell("", hdrF, blue));
            for (String n : names) cmTable.addCell(headerCell(truncate(n, 14), hdrF, blue));
            for (String n1 : names) {
                cmTable.addCell(headerCell(truncate(n1, 14), hdrF, new Color(52, 73, 94)));
                for (String n2 : names) {
                    double corr = findCorrelation(pairs, n1, n2);
                    Font f = corr > 0.7
                        ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, new Color(192, 57, 43))
                        : corr < 0.3
                            ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, new Color(39, 174, 96))
                            : normF;
                    PdfPCell c = new PdfPCell(new Phrase(fmt2(corr), f));
                    c.setHorizontalAlignment(Element.ALIGN_CENTER);
                    c.setPadding(2);
                    cmTable.addCell(c);
                }
            }
            doc.add(cmTable);
            doc.add(new Paragraph(" "));

            // ── Drawdown Overlap ──
            doc.add(new Paragraph("Drawdown Overlap", subF));
            doc.add(new Paragraph(" "));
            List<CorrelationMatrix.Pair> ddPairs = correlationMatrix.computeDrawdownCorrelation();
            PdfPTable ddTable = new PdfPTable(3);
            ddTable.setWidthPercentage(100);
            row(ddTable, "Strategy A", "Strategy B", "Overlap Ratio", hdrF, normF, blue);
            for (CorrelationMatrix.Pair p : ddPairs) {
                row(ddTable, p.strategyA(), p.strategyB(), fmt2(p.correlation()), hdrF, normF, gray);
            }
            doc.add(ddTable);
            doc.add(new Paragraph(" "));
        }

        // ── Portfolio Allocations ──
        if (equalWeightPortfolio != null) {
            doc.add(new Paragraph("Portfolio Allocations", subF));
            doc.add(new Paragraph(" "));

            PdfPTable allocTable = new PdfPTable(3);
            allocTable.setWidthPercentage(100);
            row(allocTable, "Strategy", "Equal Weight", "Max Sharpe", hdrF, normF, blue);
            for (String n : names) {
                double ew = equalWeightPortfolio.weights().getOrDefault(n, 0.0) * 100;
                double ms = maxSharpePortfolio != null
                    ? maxSharpePortfolio.weights().getOrDefault(n, 0.0) * 100 : 0;
                row(allocTable, n, fmt1(ew) + "%", fmt1(ms) + "%", hdrF, normF, gray);
            }
            doc.add(allocTable);
            doc.add(new Paragraph(" "));
        }

        // ── Per-Strategy Comparison Table ──
        doc.add(new Paragraph("Per-Strategy Comparison", subF));
        doc.add(new Paragraph(" "));
        PdfPTable compTable = new PdfPTable(names.size() + 1);
        compTable.setWidthPercentage(100);
        String[] metrics = {"Return %", "Sharpe", "Max DD %", "Win Rate %", "Profit Factor", "Total Trades"};
        compTable.addCell(headerCell("Metric", hdrF, blue));
        for (String n : names) compTable.addCell(headerCell(truncate(n, 14), hdrF, blue));
        for (String metric : metrics) {
            compTable.addCell(headerCell(metric, hdrF, new Color(52, 73, 94)));
            for (String n : names) {
                BacktestResult r = results.get(n);
                String val = switch (metric) {
                    case "Return %" -> fmtPct(r.totalReturnPct());
                    case "Sharpe" -> fmt2(r.sharpeRatio());
                    case "Max DD %" -> fmtPct(r.maxDrawdownPct());
                    case "Win Rate %" -> fmt1(r.winRatePct()) + "%";
                    case "Profit Factor" -> fmt2(r.profitFactor());
                    case "Total Trades" -> String.valueOf(r.totalTrades());
                    default -> "—";
                };
                PdfPCell c = new PdfPCell(new Phrase(val, normF));
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setPadding(2);
                compTable.addCell(c);
            }
        }
        doc.add(compTable);

        // ── Monte Carlo per Strategy ──
        if (!mcResults.isEmpty()) {
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("Monte Carlo Risk Analysis", subF));
            doc.add(new Paragraph(" "));
            String[] mcMetrics = {"Median P&L", "VaR 95%", "Loss Prob %", "Median DD %", "DD 95th %", "Median Sharpe"};
            PdfPTable mcTable = new PdfPTable(names.size() + 1);
            mcTable.setWidthPercentage(100);
            mcTable.addCell(headerCell("Metric", hdrF, blue));
            for (String n : names) mcTable.addCell(headerCell(truncate(n, 14), hdrF, blue));
            for (String metric : mcMetrics) {
                mcTable.addCell(headerCell(metric, hdrF, new Color(52, 73, 94)));
                for (String n : names) {
                    MonteCarloSimulation.Result mc = mcResults.get(n);
                    String val;
                    if (mc == null) {
                        val = "—";
                    } else {
                        val = switch (metric) {
                            case "Median P&L" -> "$" + fmt0(mc.medianPnl());
                            case "VaR 95%" -> "$" + fmt0(mc.var95());
                            case "Loss Prob %" -> fmt1(mc.probabilityOfLoss()) + "%";
                            case "Median DD %" -> fmt2(mc.medianDrawdown()) + "%";
                            case "DD 95th %" -> fmt2(mc.drawdown95()) + "%";
                            case "Median Sharpe" -> fmt2(mc.medianSharpe());
                            default -> "—";
                        };
                    }
                    PdfPCell c = new PdfPCell(new Phrase(val, normF));
                    c.setHorizontalAlignment(Element.ALIGN_CENTER);
                    c.setPadding(2);
                    mcTable.addCell(c);
                }
            }
            doc.add(mcTable);
        }

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("Generated by Trading Bridge Portfolio Analyzer | "
            + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), smallF));
        doc.close();
        System.out.println("Portfolio PDF: " + path.toAbsolutePath());
        return path;
    }

    // ───────────────────────────────────────────────────────────
    //  CHART — Overlay of multiple equity curves + combined
    // ───────────────────────────────────────────────────────────

    private Path drawOverlayChart(
            Map<String, BacktestResult> results,
            List<Double> combinedEquity) throws Exception {
        Path png = Files.createTempFile("portfolio_eq_", ".png");
        List<String> names = results.keySet().stream().sorted().toList();
        int n = combinedEquity.size();
        int w = CHART_W, h = CHART_H;
        int marginL = 60, marginR = 20, marginT = 15, marginB = 35;
        int plotW = w - marginL - marginR;
        int plotH = h - marginT - marginB;

        // Find global min/max across all curves + combined
        double globalMin = Double.MAX_VALUE;
        double globalMax = -Double.MAX_VALUE;
        for (String name : names) {
            for (double e : results.get(name).equityCurve()) {
                if (e < globalMin) globalMin = e;
                if (e > globalMax) globalMax = e;
            }
        }
        for (double e : combinedEquity) {
            if (e < globalMin) globalMin = e;
            if (e > globalMax) globalMax = e;
        }
        double padding = Math.max((globalMax - globalMin) * 0.05, globalMin * 0.01);
        if (padding == 0) padding = 1;
        globalMin -= padding;
        globalMax += padding;
        double range = globalMax - globalMin;
        int lastIdx = Math.max(n - 1, 1);

        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(COLOR_BG);
        g.fillRect(0, 0, w, h);

        // Grid
        g.setColor(COLOR_GRID);
        for (int row = 0; row <= 4; row++) {
            int y = marginT + plotH * row / 4;
            g.drawLine(marginL, y, w - marginR, y);
        }

        // Y-axis labels
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 9));
        g.setColor(Color.DARK_GRAY);
        for (int row = 0; row <= 4; row++) {
            double frac = (4.0 - row) / 4.0;
            double v = globalMin + frac * range;
            int y = marginT + plotH * row / 4;
            String lbl = "$" + (v >= 1000 ? Math.round(v) : String.format("%.2f", v));
            int tw = g.getFontMetrics().stringWidth(lbl);
            g.drawString(lbl, marginL - tw - 4, y + 3);
        }

        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 10));

        // Draw individual strategy curves
        int ci = 0;
        for (String name : names) {
            List<Double> curve = results.get(name).equityCurve();
            Color c = STRAT_COLORS[ci % STRAT_COLORS.length];
            ci++;
            g.setColor(c);
            g.setStroke(new BasicStroke(1.0f));
            Path2D path = new Path2D.Double();
            int curveN = Math.min(curve.size(), n);
            for (int i = 0; i < curveN; i++) {
                double x = marginL + ((double) i / lastIdx) * plotW;
                double y = marginT + plotH - scale(curve.get(i), globalMin, globalMax, plotH);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            g.draw(path);
        }

        // Draw combined curve (thicker)
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(new Color(0, 0, 0));
        Path2D combinedPath = new Path2D.Double();
        for (int i = 0; i < n; i++) {
            double x = marginL + ((double) i / lastIdx) * plotW;
            double y = marginT + plotH - scale(combinedEquity.get(i), globalMin, globalMax, plotH);
            if (i == 0) combinedPath.moveTo(x, y); else combinedPath.lineTo(x, y);
        }
        g.draw(combinedPath);

        // Legend
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 8));
        int ly = marginT + 12;
        int lx = marginL + 5;
        int sw = 12;
        ci = 0;
        for (String name : names) {
            g.setColor(STRAT_COLORS[ci % STRAT_COLORS.length]);
            ci++;
            g.fillRect(lx, ly - 5, sw, 3);
            g.setColor(Color.DARK_GRAY);
            g.drawString(truncate(name, 16), lx + sw + 3, ly);
            ly += 11;
        }
        g.setColor(Color.BLACK);
        g.fillRect(lx, ly - 5, sw, 3);
        g.setColor(Color.DARK_GRAY);
        g.drawString("Combined", lx + sw + 3, ly);

        // X-axis labels
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 8));
        g.setColor(Color.DARK_GRAY);
        for (int col = 0; col <= 4; col++) {
            int idx = Math.min(col * (n - 1) / 4, n - 1);
            double x = marginL + ((double) idx / lastIdx) * plotW;
            g.drawString("Bar " + idx, (int) x - 12, marginT + plotH + 16);
        }

        g.dispose();
        ImageIO.write(bi, "PNG", png.toFile());
        return png;
    }

    // ───────────────────────────────────────────────────────────
    //  Portfolio Math
    // ───────────────────────────────────────────────────────────

    /**
     * Computes the equal-weight combined equity curve from multiple results.
     * All curves must have the same length (same number of bars).
     */
    static List<Double> computeCombinedEquity(Map<String, BacktestResult> results) {
        if (results.isEmpty()) return List.of();
        List<String> names = results.keySet().stream().sorted().toList();
        int minLen = results.values().stream()
            .mapToInt(r -> r.equityCurve().size())
            .min().orElse(0);
        if (minLen < 2) {
            // Fall back to the first result
            return results.values().iterator().next().equityCurve();
        }
        List<Double> combined = new ArrayList<>(minLen);
        for (int i = 0; i < minLen; i++) {
            double sum = 0;
            for (String n : names) {
                sum += results.get(n).equityCurve().get(i);
            }
            combined.add(sum / names.size());
        }
        return combined;
    }

    private double computeCombinedSharpe(List<Double> combinedEquity) {
        if (combinedEquity.size() < 2) return 0;
        List<Double> returns = new ArrayList<>(combinedEquity.size() - 1);
        for (int i = 1; i < combinedEquity.size(); i++) {
            double prev = combinedEquity.get(i - 1);
            if (prev != 0) returns.add((combinedEquity.get(i) - prev) / prev);
        }
        // Use the first result's periodsPerYear for annualisation
        double ppy = firstResult().periodsPerYear();
        return PerformanceMetrics.sharpeRatio(returns, PerformanceMetrics.DEFAULT_RISK_FREE_RATE, ppy);
    }

    private double computeMaxDrawdown(List<Double> equity) {
        double peak = Double.NEGATIVE_INFINITY;
        double maxDd = 0;
        for (double e : equity) {
            if (e > peak) peak = e;
            double dd = peak > 0 ? (peak - e) / peak * 100 : 0;
            if (dd > maxDd) maxDd = dd;
        }
        return maxDd;
    }

    private double computeAvgCorrelation() {
        if (correlationMatrix == null) return 0;
        List<CorrelationMatrix.Pair> pairs = correlationMatrix.computePnLCorrelation();
        return pairs.stream()
            .filter(p -> !Double.isNaN(p.correlation()))
            .mapToDouble(CorrelationMatrix.Pair::correlation)
            .average().orElse(0);
    }

    /**
     * Diversification Ratio = (average individual Sharpe) / (combined Sharpe).
     * Values > 1.0 indicate diversification benefit.
     */
    private double computeDiversificationRatio() {
        Map<String, Double> sharpeMap = new HashMap<>();
        for (var entry : results.entrySet()) {
            sharpeMap.put(entry.getKey(), entry.getValue().sharpeRatio());
        }
        List<Double> combinedEq = computeCombinedEquity(results);
        double combinedSharpe = computeCombinedSharpe(combinedEq);
        if (combinedSharpe <= 0) return 0;
        double avgSharpe = sharpeMap.values().stream()
            .mapToDouble(d -> d).average().orElse(0);
        if (avgSharpe <= 0) return 0;
        return avgSharpe / combinedSharpe;
    }

    private double findCorrelation(List<CorrelationMatrix.Pair> pairs, String a, String b) {
        if (a.equals(b)) return 1.0;
        for (CorrelationMatrix.Pair p : pairs) {
            if ((p.strategyA().equals(a) && p.strategyB().equals(b))
                || (p.strategyA().equals(b) && p.strategyB().equals(a))) {
                return p.correlation();
            }
        }
        return Double.NaN;
    }

    private BacktestResult firstResult() {
        return results.values().iterator().next();
    }

    // ───────────────────────────────────────────────────────────
    //  PDF Helpers
    // ───────────────────────────────────────────────────────────

    private void row(PdfPTable t, String l, String v, Font h, Font n, Color bg) {
        PdfPCell lc = new PdfPCell(new Phrase(l, h)); lc.setBackgroundColor(bg); t.addCell(lc);
        PdfPCell vc = new PdfPCell(new Phrase(v, n));
        vc.setHorizontalAlignment(Element.ALIGN_RIGHT); t.addCell(vc);
    }

    private void row(PdfPTable t, String a, String b, String c, Font h, Font n, Color bg) {
        PdfPCell ca = new PdfPCell(new Phrase(a, h)); ca.setBackgroundColor(bg); t.addCell(ca);
        PdfPCell cb = new PdfPCell(new Phrase(b, h)); cb.setBackgroundColor(bg);
        cb.setHorizontalAlignment(Element.ALIGN_CENTER); t.addCell(cb);
        PdfPCell cc = new PdfPCell(new Phrase(c, h)); cc.setBackgroundColor(bg);
        cc.setHorizontalAlignment(Element.ALIGN_CENTER); t.addCell(cc);
    }

    private PdfPCell headerCell(String text, Font f, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(bg);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setPadding(3);
        c.getPhrase().getFont().setColor(Color.WHITE);
        return c;
    }

    private static double scale(double v, double lo, double hi, double px) {
        return (v - lo) / (hi - lo) * px;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 2) + ".." : (s != null ? s : "");
    }

    private String fmtPct(double v) { return String.format("%.2f%%", v); }
    private String fmt2(double v)  { return String.format("%.2f", v); }
    private String fmt1(double v)  { return String.format("%.1f", v); }
    private String fmt0(double v)  { return String.format("%.0f", v); }
}
