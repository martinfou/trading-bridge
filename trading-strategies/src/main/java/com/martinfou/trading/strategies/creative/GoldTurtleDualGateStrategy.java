package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldTurtleDualGate — GoldTurtle (Donchian 55/20, XAU/USD H1) conditionné par un
 * GATE À DEUX JAMBES (2 séries de régime, modes AND / OR / mono-jambe).
 *
 * 📊 CONCEPT (idée nouvelle, lundi 14 septembre 2026 — 35e résultat). Suite directe du
 *    10 sept (GoldIndexRegime, EXPLORE) : la DÉRIVE forward de l'or se concentre dans le
 *    quadrant « USD ferme × stress actions » (fwd21 +2.061% hit 63.2%, 89 épisodes,
 *    IS +1.788% → OOS +2.591%, hors crises +3.608% hit 70.3%) — mais le MÊME concept en
 *    gate d'entrée mono-jambe (SPX seul) est REJETÉ (le WF INVERSE la direction :
 *    ALIGNED 1.29 → 0.91). Dérive ≠ tradeabilité.
 *
 *    Question jamais testée : le quadrant twin est-il ÉNUMÉRABLE comme une
 *    COMBINAISON BOOLÉENNE de deux gauges (A = DXY synthétique « USD ferme »,
 *    B = S&P 500 « stress actions ») ? Un gate à deux jambes (AND = intersection des
 *    deux conditions, OR = union) porte-t-il de l'information que ni l'une ni l'autre
 *    jambe seule ne porte ?
 *
 *    C'est LE véhicule manquant identifié par la revue hebdo du 12 sept
 *    (« gate à deux jambes : classe dédiée 2 séries + modes AND/OR ») — la validation
 *    d'implémentation imposée est que le mode MONO-JAMBE A (DXY) doit reproduire
 *    EXACTEMENT le résultat du 28 août (OPPOSITE SMA500 → PF 1.34 / 830 trades / +$18 143).
 *
 * 🔧 SÉMANTIQUE (chaque jambe = « niveau vs sa moyenne mobile », look-ahead safe) :
 *    - LegBull_i = (valeur_i < SMA_i)  → « or haussier » au sens naïf de la jambe i
 *      (A : USD faible → or fort ; B : actions en stress → or refuge).
 *    - POLARITY_i = ALIGNED  : bullish_i = LegBull_i
 *      POLARITY_i = OPPOSITE : bullish_i = !LegBull_i   (contrôle directionnel)
 *    - BUY autorisé si bullCondition ; SELL autorisé si !bullCondition
 *      où bullCondition = combinaison (AND / OR / mono) des bullish_i.
 *
 *    Modes : MODE_OFF (baseline) / MODE_A_ONLY / MODE_B_ONLY / MODE_AND / MODE_OR.
 *
 * 🎯 Lecture attendue :
 *    - AND(A=OPPOSITE « USD ferme », B=ALIGNED « stress SPX ») = le quadrant twin du
 *      10 sept → si le net/PF décolle au-dessus de A seul (1.34), la dérive EST
 *      énumérable comme conjonction booléenne → config famille or mise à jour.
 *    - AND ≈ A seul, ou AND négatif → la 2e jambe n'ajoute rien à la SÉLECTION
 *      (elle ajoute de l'information de DÉRIVE, pas de TIMING d'entrée) → confirme
 *      « dérive ≠ tradeabilité » avec une preuve bidirectionnelle.
 *    - OR > mono-jambes → l'information est dans l'UNION (les deux régimes sont
 *      alternativement porteurs) → piste neuve.
 *
 * MÉCANIQUE Turtle identique à GoldTurtleTrend / GoldTurtleDxyFilter (canaux et SMA
 * calculés sur les barres/valeurs STRICTEMENT antérieures ; les deux pointeurs de série
 * n'avancent que sur ts < ts(barre courante)). Quantity fixe 10 oz — jamais de
 * calcRiskPosition ATR (pitfall AugustRiskFade).
 *
 * 🔁 GÉNÉRALISATION « GATE → OVERLAY DE TAILLE » (mardi 15 septembre 2026, 36e résultat) :
 *    Le gate AND/twin a échoué comme SÉLECTEUR d'entrées (PF 1.01, WF OOS 0.84) alors que la
 *    DÉRIVE forward du quadrant twin est validée (10 sept, EXPLORE). C'est LA piste restante
 *    de la revue du 12 sept : le twin comme MODULATEUR DE TAILLE, pas comme gate d'entrée.
 *
 *    Deux paramètres généralisent la classe (`unitsAllowed` / `unitsBlocked`) :
 *      - (unitsAllowed=1, unitsBlocked=0) = GATE PUR  → comportement strictement identique
 *        aux résultats publiés du 14 sept (aucun trade pris quand la condition est fausse) ;
 *      - (unitsAllowed=1, unitsBlocked=2) = OVERLAY   → TOUS les breakouts Donchian sont
 *        tradés (la sélection disparaît), mais la taille d'entrée est DOUBLÉE quand la
 *        condition « refuge » est vraie pour ce sens (BUY = cond, SELL = !cond) ;
 *      - (2, 2) = GATE PUR à 2 unités = contrôle de mise à l'échelle plate (Pattern C :
 *        un amplificateur uniforme ne change ni le PF ni la qualité, il multiplie le net
 *        et le DD).
 *
 *    Question testée : l'information de régime (dérive) est-elle mieux exploitée comme
 *    AMPLITUDE que comme SÉLECTION ? Autrement dit, la moitié des breakouts que le gate
 *    refuse (trades « hors refuge ») — qui contiennent pourtant une traîne de PnL — doit-elle
 *    être gardée à taille réduite plutôt que jetée ?
 *
 *    + `sidePolicy` (BOTH / LONG_ONLY / SHORT_ONLY) teste la piste « long-only Turtle or »
 *      ouverte par la signature LONG/SHORT du 11 sept (or LONG +$21 480 / SHORT −$3 547 :
 *      le long porte l'edge, le short n'est qu'une traîne).
 */
public class GoldTurtleDualGateStrategy implements Strategy {

    // --- modes du gate ---
    public static final int MODE_OFF     = 0;   // baseline (aucun gate)
    public static final int MODE_A_ONLY  = 1;   // jambe A seule
    public static final int MODE_B_ONLY  = 2;   // jambe B seule
    public static final int MODE_AND     = 3;   // intersection (twin quadrant si polA=OPP, polB=ALIGNED)
    public static final int MODE_OR      = 4;   // union

    // --- polarités par jambe ---
    public static final int POL_ALIGNED  = 0;   // sens naïf de la jambe
    public static final int POL_OPPOSITE = 1;   // sens inverse (contrôle directionnel)

    // --- politique de côté (signature LONG/SHORT du 11 sept) ---
    public static final int SIDE_BOTH       = 0;
    public static final int SIDE_LONG_ONLY  = 1;
    public static final int SIDE_SHORT_ONLY = 2;

    private static final double QUANTITY_PER_UNIT = 10;   // oz par unité — pas de levier ATR
    private static final int DEFAULT_ENTRY_CHANNEL = 55;
    private static final int DEFAULT_EXIT_CHANNEL = 20;

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;
    private final int mode;
    private final int polarityA;
    private final int polarityB;
    private final int smaA;   // période jambe A (barres H1 de la grille)
    private final int smaB;   // période jambe B
    private final int unitsAllowed;   // unités quand la condition du gate est VRAIE pour le sens
    private final int unitsBlocked;   // unités quand elle est FAUSSE (0 = gate pur, >0 = overlay)
    private final int sidePolicy;     // SIDE_BOTH / SIDE_LONG_ONLY / SIDE_SHORT_ONLY

    private final List<Long> tsA = new ArrayList<>();
    private final List<Double> valA = new ArrayList<>();
    private final List<Long> tsB = new ArrayList<>();
    private final List<Double> valB = new ArrayList<>();
    private int ptrA = -1;
    private int ptrB = -1;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private int openUnits = 0;

    /** Compteurs d'exposition (diagnostic : part des trades pris à taille pleine vs réduite). */
    private int entriesAllowed = 0;
    private int entriesBlocked = 0;

    public GoldTurtleDualGateStrategy(String name, String symbol, Map<Long, Double> seriesA,
                                      Map<Long, Double> seriesB) {
        this(name, symbol, DEFAULT_ENTRY_CHANNEL, DEFAULT_EXIT_CHANNEL,
             MODE_AND, POL_OPPOSITE, POL_ALIGNED, 500, 4800, seriesA, seriesB);
    }

    public GoldTurtleDualGateStrategy(String name, String symbol, int entryChannel, int exitChannel,
                                      int mode, int polarityA, int polarityB,
                                      int smaA, int smaB,
                                      Map<Long, Double> seriesA, Map<Long, Double> seriesB) {
        this(name, symbol, entryChannel, exitChannel, mode, polarityA, polarityB, smaA, smaB,
             1, 0, SIDE_BOTH, seriesA, seriesB);
    }

    /**
     * Constructeur généralisé « gate → overlay de taille ».
     *
     * @param unitsAllowed unités (× 10 oz) quand la condition du gate est vraie pour le sens du breakout
     * @param unitsBlocked unités quand elle est fausse : 0 = GATE PUR (comportement publié du 14 sept),
     *                     &gt; 0 = OVERLAY (le trade est pris à taille réduite/pleine au lieu d'être jeté)
     * @param sidePolicy   SIDE_BOTH / SIDE_LONG_ONLY / SIDE_SHORT_ONLY
     */
    public GoldTurtleDualGateStrategy(String name, String symbol, int entryChannel, int exitChannel,
                                      int mode, int polarityA, int polarityB,
                                      int smaA, int smaB,
                                      int unitsAllowed, int unitsBlocked, int sidePolicy,
                                      Map<Long, Double> seriesA, Map<Long, Double> seriesB) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
        this.mode = mode;
        this.polarityA = polarityA;
        this.polarityB = polarityB;
        this.smaA = Math.max(1, smaA);
        this.smaB = Math.max(1, smaB);
        this.unitsAllowed = Math.max(0, unitsAllowed);
        this.unitsBlocked = Math.max(0, unitsBlocked);
        this.sidePolicy = sidePolicy;
        fill(seriesA, tsA, valA);
        fill(seriesB, tsB, valB);
    }

    private static void fill(Map<Long, Double> src, List<Long> ts, List<Double> val) {
        if (src == null) return;
        for (Map.Entry<Long, Double> e : src.entrySet()) { ts.add(e.getKey()); val.add(e.getValue()); }
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        advance(bar.timestamp().toEpochMilli());
        history.add(bar);
        int n = history.size();
        if (n < exitChannel + 2) return;

        double prevHigh55 = maxHigh(n - 1, entryChannel);
        double prevLow55  = minLow(n - 1, entryChannel);
        double prevHigh20 = maxHigh(n - 1, exitChannel);
        double prevLow20  = minLow(n - 1, exitChannel);

        if (inTrade) {
            if (tradeDirection == Order.Side.BUY && bar.close() < prevLow20) { closePosition(bar); return; }
            if (tradeDirection == Order.Side.SELL && bar.close() > prevHigh20) { closePosition(bar); return; }
            return;
        }

        Order.Side seasonalBias = getSeasonalBias(symbol, bar.timestamp()); // XAU → null

        if (bar.close() > prevHigh55) {
            if (!sideAllowed(Order.Side.BUY)) return;
            if (seasonalBias == null || seasonalBias == Order.Side.BUY) {
                int units = gateUnits(true);
                if (units > 0) enter(bar, Order.Side.BUY, units);
            }
        } else if (bar.close() < prevLow55) {
            if (!sideAllowed(Order.Side.SELL)) return;
            if (seasonalBias == null || seasonalBias == Order.Side.SELL) {
                int units = gateUnits(false);
                if (units > 0) enter(bar, Order.Side.SELL, units);
            }
        }
    }

    /** Politique de côté (piste long-only, signature LONG/SHORT du 11 sept). */
    private boolean sideAllowed(Order.Side side) {
        if (sidePolicy == SIDE_LONG_ONLY) return side == Order.Side.BUY;
        if (sidePolicy == SIDE_SHORT_ONLY) return side == Order.Side.SELL;
        return true;
    }

    /** Avance les deux pointeurs : toutes les entrées avec ts < barre courante. */
    private void advance(long barTs) {
        while (ptrA + 1 < valA.size() && tsA.get(ptrA + 1) < barTs) ptrA++;
        while (ptrB + 1 < valB.size() && tsB.get(ptrB + 1) < barTs) ptrB++;
    }

    /**
     * Le gate à deux jambes, version AMPLITUDE. `wantBuy` = le breakout est haussier (BUY) ou
     * baissier (SELL). Retourne le nombre d'unités à engager pour ce breakout :
     *   - 0 → le trade n'est PAS pris (gate pur, comportement du 14 sept) ;
     *   - &gt;0 → le trade est pris avec cette taille (overlay de taille).
     * Une jambe sans donnée (ptr < 0) est NEUTRE : elle n'autorise ni ne bloque.
     */
    private int gateUnits(boolean wantBuy) {
        if (mode == MODE_OFF) { entriesAllowed++; return unitsAllowed; }
        Boolean a = bullA();
        Boolean b = bullB();
        boolean cond;
        switch (mode) {
            case MODE_A_ONLY -> cond = (a == null) || a;
            case MODE_B_ONLY -> cond = (b == null) || b;
            case MODE_AND -> cond = (a == null || a) && (b == null || b);
            case MODE_OR  -> cond = (a != null && a) || (b != null && b);
            default -> cond = true;
        }
        boolean allowed = wantBuy ? cond : !cond;
        if (allowed) { entriesAllowed++; return unitsAllowed; }
        entriesBlocked++;
        return unitsBlocked;
    }

    // --- diagnostics d'exposition (pour le rapport) ---

    /** Nombre d'entrées prises à la taille « condition vraie ». */
    public int entriesAllowed() { return entriesAllowed; }
    /** Nombre d'entrées prises à la taille « condition fausse » (0 en mode gate pur). */
    public int entriesBlocked() { return entriesBlocked; }

    /** true = la jambe A penche « or haussier » ; null = pas encore de donnée. */
    private Boolean bullA() { return bull(valA, ptrA, smaA, polarityA); }
    private Boolean bullB() { return bull(valB, ptrB, smaB, polarityB); }

    private static Boolean bull(List<Double> val, int ptr, int smaPeriod, int polarity) {
        if (ptr < 0) return null;
        int from = Math.max(0, ptr - smaPeriod + 1);
        double sum = 0;
        for (int i = from; i <= ptr; i++) sum += val.get(i);
        double sma = sum / (ptr - from + 1);
        boolean legBull = val.get(ptr) < sma;   // niveau sous sa moyenne → « or haussier » naïf
        return polarity == POL_OPPOSITE ? !legBull : legBull;
    }

    private void enter(Bar bar, Order.Side side, int units) {
        double qty = units * QUANTITY_PER_UNIT;
        pending.add(new Order(symbol, side, Order.Type.MARKET, qty, bar.close()));
        inTrade = true;
        tradeDirection = side;
        openUnits = units;
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        double qty = Math.max(1, openUnits) * QUANTITY_PER_UNIT;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, qty, bar.close()).closeOnly());
        inTrade = false;
        openUnits = 0;
    }

    private double maxHigh(int end, int period) {
        double max = 0;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) if (history.get(i).high() > max) max = history.get(i).high();
        return max;
    }

    private double minLow(int end, int period) {
        double min = Double.MAX_VALUE;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) if (history.get(i).low() < min) min = history.get(i).low();
        return min;
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    @Override public List<Order> getPendingOrders() {
        var copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        openUnits = 0;
        entriesAllowed = 0;
        entriesBlocked = 0;
        ptrA = -1;
        ptrB = -1;
    }

    /**
     * Inline SeasonalityFilter — miroir exact de GoldTurtleTrendStrategy / GoldTurtlePyramidStrategy
     * (trading-strategies ne peut pas dépendre de trading-intelligence : cycle Maven). XAU_USD → neutre.
     */
    protected Order.Side getSeasonalBias(String sym, Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        String s = sym.replace("_", "");
        if (s.equals("USDCAD") && inWindow(month, day, 10, 12, 11, 26)) return Order.Side.BUY;
        if (s.equals("USDJPY") && inWindow(month, day, 9, 27, 11, 11)) return Order.Side.BUY;
        if (s.equals("GBPUSD") && inWindow(month, day, 3, 11, 4, 25)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 3, 16, 4, 30)) return Order.Side.BUY;
        if (s.equals("AUDUSD") && inWindow(month, day, 6, 4, 7, 19)) return Order.Side.BUY;
        if (s.equals("USDCAD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.SELL;
        if (s.equals("GBPUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        return null;
    }

    private boolean inWindow(int month, int day, int sm, int sd, int em, int ed) {
        if (sm > em || (sm == em && sd > ed)) {
            return (month > sm || (month == sm && day >= sd))
                || (month < em || (month == em && day <= ed));
        }
        return (month > sm || (month == sm && day >= sd))
            && (month < em || (month == em && day <= ed));
    }
}
