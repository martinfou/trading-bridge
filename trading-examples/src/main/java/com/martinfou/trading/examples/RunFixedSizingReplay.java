package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RealCostModel;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.core.indicators.Indicators;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.CompositeMomentumRankingStrategy;
import com.martinfou.trading.strategies.creative.ConsecutiveBarExhaustionStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtleDualGateStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtleDxyFilterStrategy;
import com.martinfou.trading.strategies.creative.GoldWeekdayDxyStrategy;
import com.martinfou.trading.strategies.creative.MonthWeekPhaseStrategy;
import com.martinfou.trading.strategies.creative.VWPReversionStrategy;
import com.martinfou.trading.strategies.longterm.LtRSI3Momentum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * RunFixedSizingReplay — rejeu des 8 stratégies (3 candidats or + 5 déployées) au sizing
 * FIXE de la décision D37 : 1 % de risque par transaction, capital identique 10 000 $.
 *
 * <p>Sizing : {@code units = (0.01 × capital) / distanceAuStop}. Le capital (10 000 $) et le
 * risque (1 %) sont constants ; seule la taille varie. Si l'ordre d'entrée porte déjà un stop
 * (champ interne / stop ATR), on utilise CE stop comme dénominateur. Sinon (sortie sur le temps,
 * comme les sessions vendredi/or, ou sortie canal Turtle), on invente un stop ATR(14) et on le
 * signale explicitement ({@code inventedStopUsed}).
 *
 * <p>Modèle de coûts : {@link RealCostModel} (commission 0, demi-spread mesuré par jambe, swap =
 * fraction annuelle) aux DEUX niveaux de spread — médiane mesurée et conservateur ×1.5 — comme le
 * rejeu du shortlist.
 *
 * <p>Modes :
 * <ul>
 *   <li>{@code --validate} : rejoue les stratégies à taille FIXE de référence (10 oz or, 1000
 *       unités FX, sizing interne pour ltrsi3) pour reproduire les références publiées et
 *       valider le harnais (données + fenêtres + coûts).</li>
 *   <li>{@code --replay} (défaut) : rejoue au sizing D37 et imprime PF/DD/trades/net aux deux
 *       niveaux de spread + le diagnostic de sizing + la preuve MAE pour les 3 candidats or.</li>
 * </ul>
 */
public class RunFixedSizingReplay {

    static final String GOLD = "XAU_USD";
    static final String GOLD_SPEC = "2006-2025";   // grille or complète (le DXY est construit dessus)
    static final double CAPITAL = 10_000.0;
    static final double RISK_PCT = 1.0;
    static final boolean[] FRI = {false, false, false, false, true};
    static final int ALIGNED = GoldTurtleDualGateStrategy.POL_ALIGNED;
    static final int OPPOSITE = GoldTurtleDualGateStrategy.POL_OPPOSITE;
    static final int SIDE_BOTH = GoldTurtleDualGateStrategy.SIDE_BOTH;

    record Window(String name, int y0, int y1) {}
    static final List<Window> WINDOWS = List.of(
        new Window("FULL", 2010, 2025),
        new Window("IS", 2010, 2018),
        new Window("OOS1", 2019, 2022),
        new Window("OOS2", 2023, 2025)
    );

    interface StratFactory { Strategy build(); }

    record Strat(String key, String symbol, boolean gold, StratFactory f) {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--replay";
        if (mode.equals("--validate")) { validate(); return; }
        replay();
    }

    // ------------------------------------------------------------------ coûts

    static BacktestExecutionCost costScaled(String sym, double mult) {
        double half = RealCostModel.halfSpread(sym);          // refuse les paires non mesurées
        Double ref = RealCostModel.REFERENCE_MIDS.get(sym);
        double refP = ref == null ? 1.0 : ref;
        double h = half * mult;
        return new BacktestExecutionCost(0.0, 0.0, 0.0, h, refP > 0 ? h / refP : 0.0);
    }

    /** Stratégie nue (taille interne fixe) — reproduit les références. */
    static BacktestResult runRaw(Strat s, List<Bar> bars, BacktestExecutionCost cost) {
        return RunContext.forStrategy(null, s.key(), s.f().build(), s.symbol(),
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }

    /** Sizing D37 (taille = budget de risque), sans attacher de stop. */
    static BacktestResult runSized(Strat s, List<Bar> bars, BacktestExecutionCost cost) {
        Strategy strat = new RiskSized(s.f().build(), s.symbol(), true, false);
        return RunContext.forStrategy(null, s.key(), strat, s.symbol(),
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }

    /** Taille FIXE + stop ATR(14) attaché — pour la preuve MAE (effet isolé du stop). */
    static BacktestResult runStopped(Strat s, List<Bar> bars, BacktestExecutionCost cost) {
        Strategy strat = new RiskSized(s.f().build(), s.symbol(), false, true);
        return RunContext.forStrategy(null, s.key(), strat, s.symbol(),
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }

    // ------------------------------------------------------------------ sizing

    static double usdPerQuoteUnit(String sym) { return RealCostModel.usdPerQuoteUnit(sym); }

    static double rawRiskUnits(String sym, double stopDistance) {
        double riskAmount = CAPITAL * RISK_PCT / 100.0;      // 100 USD
        return riskAmount / (stopDistance * usdPerQuoteUnit(sym));
    }

    static long riskUnits(String sym, double stopDistance) {
        double u = rawRiskUnits(sym, stopDistance);
        if (sym.startsWith("XAU") || sym.startsWith("XAG")) return Math.max(1, Math.round(u));
        return Math.max(100, Math.round(u / 100.0) * 100);
    }

    /**
     * Wrapper : retaille chaque entrée (optionnel) et/ou colle un stop ATR(14) (optionnel),
     * et referme les sorties closeOnly à la même taille. Deux interrupteurs indépendants :
     * {@code sizeByRisk} (sizing D37) et {@code attachStop} (stop ATR(14) inventé collé sur
     * l'ordre — uniquement pour la preuve MAE). Le stop distance est celui de l'ordre s'il
     * existe, sinon un ATR(14) inventé (1×).
     */
    static final class RiskSized implements Strategy {
        final Strategy inner;
        final String symbol;
        final boolean sizeByRisk;
        final boolean attachStop;
        final List<Bar> hist = new ArrayList<>();
        double openQty = 0;
        int entries = 0;
        double sumStopDist = 0, minStop = Double.MAX_VALUE, maxStop = 0;
        long minUnits = Long.MAX_VALUE, maxUnits = 0;
        double minRawUnits = Double.MAX_VALUE;
        boolean inventedStopUsed = false;

        RiskSized(Strategy inner, String symbol, boolean sizeByRisk, boolean attachStop) {
            this.inner = inner; this.symbol = symbol;
            this.sizeByRisk = sizeByRisk; this.attachStop = attachStop;
        }

        @Override public String name() { return inner.name(); }
        @Override public void onBar(Bar bar) { hist.add(bar); inner.onBar(bar); }
        @Override public void onTick(double bid, double ask, long volume) { inner.onTick(bid, ask, volume); }
        @Override public void reset() {
            hist.clear(); inner.reset(); openQty = 0; entries = 0;
            sumStopDist = 0; minStop = Double.MAX_VALUE; maxStop = 0;
            minUnits = Long.MAX_VALUE; maxUnits = 0; minRawUnits = Double.MAX_VALUE;
            inventedStopUsed = false;
        }

        @Override public List<Order> getPendingOrders() {
            List<Order> in = inner.getPendingOrders();
            List<Order> out = new ArrayList<>();
            for (Order o : in) {
                if (o.isCloseOnly()) {
                    out.add(o.rescaleQuantity(openQty));
                    continue;
                }
                double atr = Indicators.atr(hist, 14);
                if (Double.isNaN(atr) || atr <= 0) { out.add(o); continue; }
                double stopDist;
                Order oo = o;
                if (o.hasProtectiveStop()) {
                    stopDist = Math.abs(o.price() - o.stopLoss());
                } else {
                    stopDist = atr;                          // stop ATR(14) INVENTÉ (1×)
                    inventedStopUsed = true;
                    if (attachStop) {
                        double stop = o.side() == Order.Side.BUY ? o.price() - atr : o.price() + atr;
                        oo = o.withStopLoss(stop);
                    }
                }
                long q = sizeByRisk ? riskUnits(symbol, stopDist) : Math.round(o.quantity());
                openQty = q;
                entries++;
                sumStopDist += stopDist; minStop = Math.min(minStop, stopDist);
                maxStop = Math.max(maxStop, stopDist);
                if (sizeByRisk) {
                    minUnits = Math.min(minUnits, q); maxUnits = Math.max(maxUnits, q);
                    minRawUnits = Math.min(minRawUnits, rawRiskUnits(symbol, stopDist));
                }
                out.add(oo.rescaleQuantity(q));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ stratégies

    static Strat[] goldStrats(Map<Long, Double> dxy, Map<Long, Double> spx) {
        return new Strat[] {
            new Strat("gold_dualgate", GOLD, true, () ->
                new GoldTurtleDualGateStrategy("gold_dualgate", GOLD, 55, 20,
                    GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 500, 4800,
                    1, 0, SIDE_BOTH, dxy, spx)),
            new Strat("gold_turtle_dxy", GOLD, true, () ->
                new GoldTurtleDxyFilterStrategy("gold_turtle_dxy", GOLD, 55, 20,
                    GoldTurtleDxyFilterStrategy.MODE_OPPOSITE, 500, dxy)),
            new Strat("gold_weekday_dxy", GOLD, true, () ->
                new GoldWeekdayDxyStrategy("gold_weekday_dxy", GOLD, FRI,
                    GoldWeekdayDxyStrategy.MODE_OPPOSITE, 500, dxy))
        };
    }

    static Strat[] fxStrats() {
        return new Strat[] {
            new Strat("consecbar", "GBP_JPY", false, () -> new ConsecutiveBarExhaustionStrategy("consecbar", "GBP_JPY")),
            new Strat("vwpreversion", "USD_CHF", false, () -> new VWPReversionStrategy("vwpreversion", "USD_CHF")),
            new Strat("monthweekphase", "USD_JPY", false, () -> new MonthWeekPhaseStrategy("monthweekphase", "USD_JPY")),
            new Strat("compmomentum", "USD_JPY", false, () -> new CompositeMomentumRankingStrategy("compmomentum", "USD_JPY")),
            new Strat("ltrsi3", "EUR_USD", false, () -> new LtRSI3Momentum("ltrsi3", "EUR_USD"))
        };
    }

    // ------------------------------------------------------------------ données

    static List<Bar> load(String symbol, String spec) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, spec);
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) throw new IllegalStateException("PAS DE DONNÉES pour " + symbol + " " + spec);
        return bars;
    }

    static List<Bar> slice(List<Bar> bars, int y0, int y1) {
        List<Bar> out = new ArrayList<>();
        for (Bar b : bars) {
            int y = b.timestamp().atZone(java.time.ZoneOffset.UTC).getYear();
            if (y >= y0 && y <= y1) out.add(b);
        }
        return out;
    }

    // ------------------------------------------------------------------ validate

    static void validate() throws Exception {
        System.out.println("=== VALIDATE — taille FIXE de référence (10 oz or, 1000 u FX, sizing interne ltrsi3) ===");
        System.out.println("=== Doit reproduire : gold dualgate PF 1.32/683, turtle_dxy 1.28/674, weekday_dxy 1.28/471/+4022 ===");
        System.out.println("===                    consecbar FULL 0.80, monthweekphase 0.87, compmomentum 1.02, ltrsi3 0.76 ===");

        List<Bar> gold = load(GOLD, GOLD_SPEC);
        Map<Long, Double> dxy = RunGoldTurtleDualGate.buildDxy(GOLD_SPEC);
        Map<Long, Double> spx = RunGoldTurtleDualGate.buildSpxOnXauGrid(gold);

        for (Strat s : goldStrats(dxy, spx)) {
            BacktestResult r = runRaw(s, slice(gold, 2010, 2025), costScaled(s.symbol(), 1.0));
            System.out.printf("GOLD %-16s FULL: PF %.2f DD %.2f%% TR %d NET %.2f SWAP %.2f%n",
                s.key(), r.profitFactor(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl(), r.totalSwap());
        }

        for (Strat s : fxStrats()) {
            BacktestResult r = runRaw(s, load(s.symbol(), "2010-2025"), costScaled(s.symbol(), 1.0));
            System.out.printf("FX   %-16s FULL: PF %.2f DD %.2f%% TR %d NET %.2f SWAP %.2f%n",
                s.key(), r.profitFactor(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl(), r.totalSwap());
        }
        System.out.println("DONE-VALIDATE");
    }

    // ------------------------------------------------------------------ replay

    static void replay() throws Exception {
        List<Bar> gold = load(GOLD, GOLD_SPEC);
        Map<Long, Double> dxy = RunGoldTurtleDualGate.buildDxy(GOLD_SPEC);
        Map<Long, Double> spx = RunGoldTurtleDualGate.buildSpxOnXauGrid(gold);

        System.out.println("############################################################");
        System.out.println("# REJEU D37 — sizing fixe 1 % / 10 000 $, RealCostModel (médiane + ×1.5)");
        System.out.println("############################################################");

        System.out.println("\n========== CANDIDATS OR (sizing D37) ==========");
        for (Strat s : goldStrats(dxy, spx)) {
            replayOne(s, slice(gold, 2010, 2025));
        }

        System.out.println("\n========== STRATÉGIES DÉPLOYÉES (sizing D37) ==========");
        for (Strat s : fxStrats()) {
            replayOne(s, load(s.symbol(), "2010-2025"));
        }

        System.out.println("\n========== PREUVE MAE — candidats or (le stop ATR(14) lie-t-il ?) ==========");
        for (Strat s : goldStrats(dxy, spx)) {
            maeProof(s, slice(gold, 2010, 2025));
        }
        System.out.println("\nDONE-REPLAY");
    }

    static void replayOne(Strat s, List<Bar> full) {
        System.out.println("\n---------------- " + s.key() + " (" + s.symbol() + ") ----------------");
        System.out.printf("%-6s | %-7s %-7s %-7s %-11s %-11s || %-7s %-7s %-7s %-11s %-11s%n",
            "WINDOW", "PF_m", "DD_m%", "TR_m", "NET_m", "SWAP_m", "PF_x", "DD_x%", "TR_x", "NET_x", "SWAP_x");
        for (Window w : WINDOWS) {
            List<Bar> bars = slice(full, w.y0(), w.y1());
            BacktestResult rm = runSized(s, bars, costScaled(s.symbol(), 1.0));
            BacktestResult rx = runSized(s, bars, costScaled(s.symbol(), 1.5));
            System.out.printf("%-6s | %-7.2f %-7.2f %-7d %-11.2f %-11.2f || %-7.2f %-7.2f %-7d %-11.2f %-11.2f%n",
                w.name(), rm.profitFactor(), rm.maxDrawdownPct(), rm.totalTrades(), rm.totalPnl(), rm.totalSwap(),
                rx.profitFactor(), rx.maxDrawdownPct(), rx.totalTrades(), rx.totalPnl(), rx.totalSwap());
        }

        // diagnostic de sizing (sur FULL, médiane)
        RiskSized sized = new RiskSized(s.f().build(), s.symbol(), true, false);
        RunContext.forStrategy(null, s.key(), sized, s.symbol(), RunMode.BACKTEST,
            full, CAPITAL, null, costScaled(s.symbol(), 1.0)).run();
        if (sized.entries == 0) {
            System.out.println("  SIZING: 0 entrée — aucune taille calculée.");
        } else {
            double avgStop = sized.sumStopDist / sized.entries;
            System.out.printf("  SIZING: %d entrées | stop distance: moy %.4f min %.4f max %.4f | units: min %d max %d | units brutes min %.1f | inventé=%s%n",
                sized.entries, avgStop, sized.minStop, sized.maxStop, sized.minUnits, sized.maxUnits,
                sized.minRawUnits, sized.inventedStopUsed);
        }
    }

    static void maeProof(Strat s, List<Bar> full) {
        BacktestResult noStop = runRaw(s, full, costScaled(s.symbol(), 1.0));       // chemin pur (10 oz)
        BacktestResult withStop = runStopped(s, full, costScaled(s.symbol(), 1.0)); // + stop ATR(14), 10 oz

        List<Trade> trades = noStop.trades();
        if (trades == null || trades.isEmpty()) {
            System.out.println(s.key() + ": 0 trade — MAE non établi.");
            return;
        }
        int touched = 0;
        double sumMae = 0, sumStopDist = 0, maxMae = 0;
        for (Trade t : trades) {
            double mae = maxAdverse(full, t);
            double stopDist = atrAtEntry(full, t);
            sumMae += mae; sumStopDist += stopDist; maxMae = Math.max(maxMae, mae);
            if (stopDist > 0 && mae >= stopDist) touched++;
        }
        int n = trades.size();
        System.out.printf("%s: %d trades | stop touché sur %d (%.1f%%) | MAE moy %.2f max %.2f vs stop moy %.2f | PF sans stop %.2f -> avec stop %.2f | net %.0f -> %.0f | TR %d -> %d%n",
            s.key(), n, touched, 100.0 * touched / n, sumMae / n, maxMae, sumStopDist / n,
            noStop.profitFactor(), withStop.profitFactor(),
            noStop.totalPnl(), withStop.totalPnl(),
            noStop.totalTrades(), withStop.totalTrades());
    }

    static double maxAdverse(List<Bar> bars, Trade t) {
        double worst;
        if (t.side() == Order.Side.BUY) {
            worst = t.entryPrice();
            for (Bar b : bars) {
                if (b.timestamp().toEpochMilli() < t.entryTime().toEpochMilli()) continue;
                if (b.timestamp().toEpochMilli() > t.exitTime().toEpochMilli()) break;
                worst = Math.min(worst, b.low());
            }
            return t.entryPrice() - worst;
        } else {
            worst = t.entryPrice();
            for (Bar b : bars) {
                if (b.timestamp().toEpochMilli() < t.entryTime().toEpochMilli()) continue;
                if (b.timestamp().toEpochMilli() > t.exitTime().toEpochMilli()) break;
                worst = Math.max(worst, b.high());
            }
            return worst - t.entryPrice();
        }
    }

    /** ATR(14) au signal d'entrée = ATR sur les barres strictement antérieures au remplissage. */
    static double atrAtEntry(List<Bar> bars, Trade t) {
        List<Bar> prior = new ArrayList<>();
        for (Bar b : bars) {
            if (b.timestamp().toEpochMilli() >= t.entryTime().toEpochMilli()) break;
            prior.add(b);
        }
        double atr = Indicators.atr(prior, 14);
        return (Double.isNaN(atr) || atr <= 0) ? 0 : atr;
    }
}
