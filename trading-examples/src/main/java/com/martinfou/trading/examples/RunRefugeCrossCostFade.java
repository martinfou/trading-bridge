package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.SwapCalculator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * RunRefugeCrossCostFade — LUNDI 12 oct 2026 (55e résultat, rotation = IDÉE NOUVELLE).
 *
 * <p><b>La question laissée ouverte par le 54e (samedi 10 oct) :</b> le fade du vendredi SURVIT sur les
 * vraies barres H1 des crosses refuge (GBP_CHF +8.48 bp t 4.97 · EUR_CHF +5.05 t 4.52 · AUD_CHF +10.73
 * t 4.82), mais le 54e facturait chaque cross « 2 jambes » = 4.28 bp A/R et concluait que EUR_CHF — le
 * plus STABLE — ne gardait que +0.77 bp. Or un cross n'est pas synthétisé : il est COTÉ. Son coût est
 * son PROPRE spread, et personne ne l'avait mesuré (RealCostModel.halfSpread lève une exception sur
 * GBP_CHF/EUR_CHF/AUD_CHF — « repli = refus »).
 *
 * <p><b>Ce que ce run apporte :</b>
 * <ol>
 *   <li>le premier backtest MOTEUR (RunContext + BacktestEngine) du fade vendredi sur la famille refuge,
 *       avec le coût réellement mesuré chez le broker et le carry réel par ANNÉE ;</li>
 *   <li>le verdict sur l'hypothèse « cross = 2 spreads de majeures » (4.28 bp) vs spread coté mesuré ;</li>
 *   <li>les contrôles obligatoires : miroir LONG, benchmark ALLDAYS / Mon-Jeu (contrôle BÊTA), IS/OOS,
 *       bucket par décennie, ère du plancher SNB (EUR_CHF administré 2011-09 → 2015-01).</li>
 * </ol>
 *
 * <p>⚠️ Ce runner ne modifie PAS RealCostModel : il reconstruit la même formule
 * ({@code new BacktestExecutionCost(0, 0, 0, half, half / refPrice)}) avec les demi-spreads mesurés le
 * 2026-10-12, et imprime les lignes Java prêtes à être ajoutées à {@code HALF_SPREAD_PRICE}.
 *
 * <p>Usage : java -cp "$CP" com.martinfou.trading.examples.RunRefugeCrossCostFade [--all|--costs|--bench|--wf]
 */
public class RunRefugeCrossCostFade {

    static final double CAPITAL = 50_000;
    static final double QTY = 10_000;                       // convention des runners FX du pipeline
    static final ZoneId UTC = ZoneId.of("UTC");

    /** Fenêtre alignée sur celle des 8 majeures (leur H1 s'arrête au 19 mai 2026). */
    static final Instant W0 = Instant.parse("2006-01-01T00:00:00Z");
    static final Instant W1 = Instant.parse("2026-05-20T00:00:00Z");

    static final String[] CROSSES = {"GBP_CHF", "EUR_CHF", "AUD_CHF"};
    static final String[] INCUMBENTS = {"GBP_JPY", "GBP_USD"};
    static final String CONTROL = "USD_CAD";

    static final boolean[] FRI = {false, false, false, false, true};
    static final boolean[] ALLDAYS = {true, true, true, true, true};
    static final boolean[] MON_THU = {true, true, true, true, false};

    /**
     * Demi-spread mesuré par jambe (unités de PRIX) + mid de référence — OANDA practice compte -014,
     * 1000 bougies H1 {@code price=BA}, 2026-10-12. Contrôles de méthode le même jour : EUR_USD 1.60 pip
     * et GBP_JPY 3.30 pip retrouvent les valeurs FEE-AUDIT du 1er oct (1.60 / 3.40).
     */
    static final Map<String, double[]> MEASURED = new LinkedHashMap<>();

    static {
        // {demi-spread par jambe (prix), mid mesuré}
        MEASURED.put("GBP_CHF", new double[]{0.000125, 1.09766});   // spread médian 2.50 pip
        MEASURED.put("EUR_CHF", new double[]{0.000080, 0.93991});   // 1.60 pip
        MEASURED.put("AUD_CHF", new double[]{0.000075, 0.58055});   // 1.50 pip
        MEASURED.put("GBP_JPY", new double[]{0.016500, 209.85025}); // 3.30 pip (contrôle de méthode)
        MEASURED.put("GBP_USD", new double[]{0.000090, 1.34935});   // 1.80 pip, mesuré le 12 oct
        MEASURED.put("USD_CAD", new double[]{0.000090, 1.39037});   // 1.80 pip, mesuré le 12 oct
    }

    /** Différentiels annuels 3M (BASE − QUOTE) en %, 2006→2026 — table du 47e (FRED/BoC), 21 valeurs. */
    static final Map<String, double[]> DIFF = new LinkedHashMap<>();

    static {
        DIFF.put("GBP_USD", new double[]{-0.304, +0.734, +2.546, +0.658, +0.388, +0.571, +0.545, +0.346, +0.419, +0.347, -0.145, -0.794, -1.466, -1.400, -0.239, -0.024, -0.227, -0.227, -0.049, -0.047, -0.025});
        DIFF.put("AUD_USD", new double[]{+0.835, +1.399, +4.008, +2.878, +4.369, +4.544, +3.446, +2.611, +2.553, +2.029, +1.342, +0.587, -0.240, -0.863, -0.277, -0.086, -0.591, -1.198, -0.660, -0.347, +0.533});
        DIFF.put("USD_CHF", new double[]{+3.742, +2.925, +1.124, +0.462, +0.162, +0.246, +0.344, +0.214, +0.123, +0.951, +1.413, +2.000, +2.995, +2.981, +1.231, +0.850, +2.405, +3.656, +3.809, +4.084, +3.777});
        DIFF.put("USD_JPY", new double[]{+4.829, +4.522, +2.118, -0.023, -0.072, -0.029, -0.045, -0.070, -0.081, +0.057, +0.571, +1.093, +2.116, +2.176, +0.569, +0.180, +2.257, +5.174, +4.865, +3.358, +2.449});
        DIFF.put("USD_CAD", new double[]{+1.144, +1.126, +0.628, +0.224, -0.250, -0.608, -0.659, -0.799, -0.785, -0.301, +0.158, +0.464, +0.815, +0.552, +0.082, +0.001, +0.050, +0.437, +0.684, +1.592, +1.488});
        DIFF.put("EUR_USD", new double[]{-2.074, -0.991, +1.669, +0.672, +0.499, +1.087, +0.291, +0.054, +0.086, -0.246, -0.908, -1.482, -2.510, -2.564, -0.959, -0.663, -1.886, -1.747, -1.480, -1.993, -1.707});
    }

    /** 1 % de différentiel de taux = 0.274 pip/lot/jour (méthode 44e/47e). */
    static final double BP_PER_PCT_DAY = 0.274;

    static final Map<String, List<Bar>> BARS = new LinkedHashMap<>();
    static final StringBuilder REPORT = new StringBuilder();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--all";
        log("================================================================================");
        log("LES CROSSES REFUGE EN MOTEUR — coût MESURÉ, carry RÉEL par année");
        log("Lundi 12 octobre 2026 (55e) · rotation IDÉE NOUVELLE · fenêtre 2006-01-01 → 2026-05-19");
        log("SUITE 13 OCT 2026 (56e, variation) : carry " + (System.getenv("CARRY_SCALE") == null
            ? "= convention du moteur (le week-end est facturé 2x au lieu d'être porté par le mercredi)"
            : "CORRIGÉ — CARRY_SCALE=" + System.getenv("CARRY_SCALE")));
        log("================================================================================");

        loadAll();
        section("0. COUT REEL MESURE (OANDA practice -014, 12 oct 2026, 1000 bougies H1 price=BA)");
        log(String.format("%-9s %10s %12s %12s %12s", "SYMBOLE", "SPREAD pip", "HALF/jambe", "A/R (bp)", "MID"));
        for (String s : allSymbols()) {
            double half = MEASURED.get(s)[0];
            double mid = MEASURED.get(s)[1];
            double rtBp = half * 2.0 / mid * 10_000.0;
            log(String.format("%-9s %14.2f %12.6f %12.3f %12.5f", s, half * 2.0 / (mid > 100 ? 0.01 : 0.0001), half, rtBp, mid));
        }
        log("");
        log("Rappel 54e : chaque cross etait facture '2 jambes de majeures' = 4.28 bp A/R.");
        log("Un cross COTE se paie son propre spread : la question est de savoir si ca change le verdict.");

        switch (mode) {
            case "--costs" -> fadeOnly();
            case "--bench" -> benchOnly();
            case "--wf" -> windows();
            default -> {
                fadeOnly();
                benchOnly();
                windows();
            }
        }
        System.out.println("\n===== RAPPORT COMPLET (" + REPORT.length() + " chars) =====");
        System.out.println(REPORT);

        Path out = Path.of(System.getProperty("user.home"),
            "projects/tb-research/reports/" + outName() + ".txt");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, REPORT.toString());
            System.out.println("ECRIT -> " + out);
        } catch (Exception e) {
            System.out.println("(rapport non ecrit : " + e.getMessage() + ")");
        }
    }

    // ------------------------------------------------------------------ 1. fade + miroir

    static void fadeOnly() throws Exception {
        section("1. LE FADE (SELL vendredi, sortie lundi) vs MIROIR LONG — coût mesuré + carry réel par année");
        log(String.format("%-9s | %-40s | %-34s", "", "SELL FRI (la config du 50e/53e/54e)", "LONG FRI (miroir = contrôle)"));
        log(String.format("%-9s | %6s %5s %6s %7s %10s %10s | %6s %5s %10s", "SYMBOLE", "PF", "WR%", "DD%", "TRADES", "NET$", "PRIX$", "PF", "WR%", "NET$"));
        for (String s : allSymbols()) {
            Result sh = run(s, FRI, Order.Side.SELL);
            Result lo = run(s, FRI, Order.Side.BUY);
            if (sh == null || lo == null) { log(s + " : pas de donnees"); continue; }
            log(String.format("%-9s | %6.2f %4.0f%% %5.2f%% %7d %10.2f %10.2f | %6.2f %4.0f%% %10.2f",
                s, sh.pf, sh.wr, sh.dd, sh.trades, sh.net, sh.price, lo.pf, lo.wr, lo.net));
        }
    }

    // ------------------------------------------------------------------ 2. contrôles BÊTA

    static void benchOnly() throws Exception {
        section("2. CONTROLE BETA sur les crosses — vendredi vs TOUS les jours vs Mon-Jeu (mêmes coûts)");
        log(String.format("%-9s | %-30s | %-30s | %-30s", "", "SELL vendredi", "SELL tous les jours", "SELL Mon-Jeu"));
        log(String.format("%-9s | %6s %6s %10s | %6s %6s %10s | %6s %6s %10s", "SYMBOLE", "PF", "TRADES", "NET$", "PF", "TRADES", "NET$", "PF", "TRADES", "NET$"));
        for (String s : allSymbols()) {
            Result f = run(s, FRI, Order.Side.SELL);
            Result a = run(s, ALLDAYS, Order.Side.SELL);
            Result m = run(s, MON_THU, Order.Side.SELL);
            if (f == null || a == null || m == null) continue;
            log(String.format("%-9s | %6.2f %6d %10.2f | %6.2f %6d %10.2f | %6.2f %6d %10.2f",
                s, f.pf, f.trades, f.net, a.pf, a.trades, a.net, m.pf, m.trades, m.net));
        }
    }

    // ------------------------------------------------------------------ 3. IS/OOS + décennies

    static void windows() throws Exception {
        section("3. WALK-FORWARD + DECENNIES + ERE DU PLANCHER SNB (SELL vendredi, mêmes coûts)");
        String[][] w = {
            {"FULL 2006-2026", "2006-01-01T00:00:00Z", "2026-05-20T00:00:00Z"},
            {"IS 2006-2015", "2006-01-01T00:00:00Z", "2016-01-01T00:00:00Z"},
            {"OOS 2016-2026", "2016-01-01T00:00:00Z", "2026-05-20T00:00:00Z"},
            {"2006-2009", "2006-01-01T00:00:00Z", "2010-01-01T00:00:00Z"},
            {"2010-2014", "2010-01-01T00:00:00Z", "2015-01-01T00:00:00Z"},
            {"2015-2019", "2015-01-01T00:00:00Z", "2020-01-01T00:00:00Z"},
            {"2020-2026", "2020-01-01T00:00:00Z", "2026-05-20T00:00:00Z"},
            {"PEG SNB 09/2011-06/2015", "2011-09-06T00:00:00Z", "2015-06-30T00:00:00Z"},
            {"HORS PEG", "2006-01-01T00:00:00Z", "2011-09-06T00:00:00Z"},
        };
        log(String.format("%-26s | %s", "", "GBP_CHF / EUR_CHF / AUD_CHF — PF (trades, net$)"));
        for (String[] row : w) {
            StringBuilder sb = new StringBuilder(String.format("%-26s |", row[0]));
            for (String s : CROSSES) {
                Result r = runWindow(s, FRI, Order.Side.SELL,
                    Instant.parse(row[1]), Instant.parse(row[2]));
                sb.append(r == null ? " —" : String.format(" %5.2f (%d, %+.0f)", r.pf, r.trades, r.net));
            }
            log(sb.toString());
        }
    }

    // ------------------------------------------------------------------ moteur

    static Result run(String sym, boolean[] days, Order.Side side) throws Exception {
        return runWindow(sym, days, side, W0, W1);
    }

    static Result runWindow(String sym, boolean[] days, Order.Side side, Instant from, Instant to)
            throws Exception {
        List<Bar> bars = slice(BARS.get(sym), from, to);
        if (bars == null || bars.size() < 100) return null;
        double half = MEASURED.get(sym)[0];
        double mid = MEASURED.get(sym)[1];
        BacktestExecutionCost cost = new BacktestExecutionCost(0.0, 0.0, 0.0, half, half / mid);
        SwapCalculator.clearRateOverride();
        SwapCalculator.setYearlyRateOverride(sym, yearlyCarry(sym));
        try {
            var strat = new GoldWeekdayEffectStrategy("RefugeCrossFade", sym, days, side).withQuantity(QTY);
            BacktestResult r = RunContext.forStrategy(null, "RefugeCrossFade", strat, sym,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            double net = r.totalPnl();
            double swap = r.totalSwap();
            return new Result(r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                net, swap, net - swap, r.totalSlippage(), r.totalCommission());
        } finally {
            SwapCalculator.clearRateOverride();
        }
    }

    /**
     * VARIATION DU 13 OCT 2026 (56e) : facteur correctif du carry par paire, lu dans
     * l'environnement ({@code CARRY_SCALE="GBP_CHF=0.505,EUR_CHF=0.514,..."}).
     *
     * <p>Le compteur de rollovers du moteur facture CHAQUE jour calendaire a 17:00 ET (+3 le
     * mercredi) : le week-end est donc facture une 2e fois au lieu d'etre porte par le mercredi.
     * Mesure du 13 oct (scripts/rollover-daycount.py) sur les instants d'entree/sortie EXACTS de
     * ce fade : les crosses sont factures 2 jours la ou le marche en facture 1 (989/1058
     * evenements) ⇒ facteur 0.505-0.524 ; les 3 majeurs sortent samedi 00:00 UTC (barres de
     * week-end synthetiques Dukascopy) AVANT le roll du samedi ⇒ facteur 0.998 (non touches).
     * Le facteur applique aux taux injectes reproduit exactement la charge reelle, car le swap
     * est lineaire dans le taux et le compte de jours est constant par evenement.
     */
    static double carryScale(String sym) {
        String env = System.getenv("CARRY_SCALE");
        if (env == null || env.isBlank()) return 1.0;
        for (String kv : env.split(",")) {
            String[] p = kv.split("=");
            if (p.length == 2 && p[0].trim().replace("_", "").equalsIgnoreCase(sym.replace("_", ""))) {
                return Double.parseDouble(p[1].trim());
            }
        }
        return 1.0;
    }

    static Map<Integer, double[]> yearlyCarry(String sym) {
        double[] diff;
        String[] p = sym.split("_");
        String base = p[0], quote = p[1];
        if (quote.equals("CHF")) {
            diff = add(DIFF.get(base + "_USD"), DIFF.get("USD_CHF"));
        } else if (quote.equals("JPY")) {
            diff = add(DIFF.get(base + "_USD"), DIFF.get("USD_JPY"));
        } else {
            diff = DIFF.get(sym);
        }
        Map<Integer, double[]> out = new TreeMap<>();
        double k = carryScale(sym);
        for (int i = 0; i < diff.length; i++) {
            double d = diff[i];
            // long base = credite r_base − r_quote ; short = le debit inverse.
            out.put(2006 + i, new double[]{d * BP_PER_PCT_DAY * k, -d * BP_PER_PCT_DAY * k});
        }
        out.put(-1, new double[]{diff[diff.length - 1] * BP_PER_PCT_DAY * k,
                                -diff[diff.length - 1] * BP_PER_PCT_DAY * k});
        return out;
    }

    static double[] add(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] + b[i];
        return out;
    }

    // ------------------------------------------------------------------ données

    static List<String> allSymbols() {
        List<String> out = new ArrayList<>();
        out.addAll(List.of(CROSSES));
        out.addAll(List.of(INCUMBENTS));
        out.add(CONTROL);
        return out;
    }

    static void loadAll() throws Exception {
        for (String sym : allSymbols()) {
            List<Bar> bars;
            Path csv = crossCsv(sym);
            if (csv != null) {
                bars = HistoricalDataLoader.loadFile(csv, sym).bars();
                log("[data] " + sym + " <- " + csv.getFileName());
            } else {
                bars = HistoricalDataLoader.loadFromArgs(sym, sym, "2006-2026").bars();
            }
            if (bars == null || bars.isEmpty()) { log("[data] PAS DE DONNEES " + sym); continue; }
            bars.sort(Comparator.comparing(Bar::timestamp));
            BARS.put(sym, bars);
            log(String.format("[data] %-9s %6d barres  %s -> %s", sym, bars.size(),
                bars.get(0).timestamp(), bars.get(bars.size() - 1).timestamp()));
        }
    }

    /** Le CSV OANDA 2002→2026 d'un cross est UN SEUL fichier agrégé : le charger une fois, jamais par année
     *  (findDukascopyCsv matcherait ce fichier pour chaque millésime et dupliquerait la série 21 fois). */
    static Path crossCsv(String sym) throws Exception {
        Path dir = Path.of("data/historical/dukascopy");
        if (!Files.isDirectory(dir)) return null;
        String prefix = sym.replace("_", "").toLowerCase() + "-h1";
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.startsWith(prefix) && n.contains("oanda") && n.endsWith(".csv");
            }).findFirst().orElse(null);
        }
    }

    static List<Bar> slice(List<Bar> bars, Instant from, Instant to) {
        if (bars == null) return null;
        List<Bar> out = new ArrayList<>();
        for (Bar b : bars) {
            Instant t = b.timestamp();
            if (!t.isBefore(from) && t.isBefore(to)) out.add(b);
        }
        return out;
    }

    record Result(double pf, double wr, double dd, int trades, double net, double swap,
                  double price, double slip, double comm) {}

    static String outName() {
        String suffix = System.getenv("OUT_SUFFIX");
        return (suffix == null || suffix.isBlank())
            ? "2026-10-12-refuge-cross-costs"
            : "2026-10-13-refuge-cross-carrydays-" + suffix;
    }

    static void section(String s) {
        log("");
        log("--- " + s);
    }

    static void log(String s) {
        System.out.println(s);
        REPORT.append(s).append('\n');
    }
}
