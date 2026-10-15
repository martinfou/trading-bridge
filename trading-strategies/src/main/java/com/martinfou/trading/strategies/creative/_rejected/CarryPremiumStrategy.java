package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * CarryPremiumStrategy — Carry Trade (edge fondamental, pas un pattern H1)
 *
 * 📊 Inspiration: Le carry trade est le seul edge fondamental du forex — le
 *    différentiel de taux d'intérêt. On achète la devise à haut taux (AUD, NZD,
 *    USD) contre celle à bas taux (JPY, EUR, GBP) et on encaisse le swap quotidien.
 *    Ce n'est PAS un indicateur technique : le swap est un crédit déterministe
 *    tant que la position reste ouverte overnight.
 *
 * 🔧 Mechanism:
 *    - Direction du carry dérivée des taux de swap inline (mirror SwapCalculator):
 *      longSwap > 0 → BUY (on est payé pour tenir un long)
 *      shortSwap > 0 → SELL (on est payé pour tenir un short)
 *    - Filtre de tendance EMA(200) H1 (~8 jours) : on ne tient un carry QUE dans
 *      la direction de la tendance — évite les carry unwinds (krach 2008, 2020)
 *    - Entrée : prix au-dessus (BUY) / en dessous (SELL) de l'EMA + pas de biais
 *      saisonnier opposé (inline SeasonalityFilter)
 *    - Sortie : cassure de l'EMA, stop ATR(3×), ou max hold 30 jours
 *    - Pas de TP : le carry trade n'a pas de take-profit — on tient tant que la
 *      tendance tient, le swap s'accumule chaque nuit
 *
 * ⚠️ Détails moteur critiques:
 *    - Sortie TOUJOURS via ordre closeOnly() → le swap n'est crédité que dans
 *      reduceOppositeSide() (BacktestEngine), PAS dans closePosition() (SL/TP moteur)
 *    - Pas de .withStopLoss()/.withTakeProfit() sur l'ordre d'entrée : les stops
 *      attachés sont fermés par checkStopLossesTakeProfits → closePosition → swap perdu
 *    - Stop géré manuellement dans onBar() (pattern HMMRegimeMomentumStrategy)
 *    - Look-ahead: indicateurs calculés sur l'historique AVANT d'ajouter la barre
 *
 * 🎯 Originality: Première stratégie carry du catalogue. Exploite le swap
 *    correctement implémenté (fix bcced4d6 — JPY pip conversion + signed swap).
 *    Edge fondamental multi-semaines, exactement ce que l'Empirical Reality
 *    recommande après l'échec des patterns H1 purs.
 */
public class CarryPremiumStrategy implements Strategy {

    private static final int EMA_PERIOD_DEFAULT = 200; // filtre tendance H1 (~8 jours)
    private static final int ATR_PERIOD = 14;
    private static final double ATR_STOP_MULT = 3.0;  // stop large (carry tolère le bruit)
    private static final int MAX_HOLD_BARS = 24 * 30; // 30 jours max
    private static final int COOLDOWN_BARS = 24;      // 1 jour entre trades
    private static final double POSITION_UNITS = 10_000; // 0.1 lot fixe

    // Inline swap rates (pips/standard lot/day) — mirror SwapCalculator
    private static final Map<String, double[]> SWAP_RATES = new HashMap<>();
    static {
        SWAP_RATES.put("EUR_USD",  new double[]{-3.5,  1.2});
        SWAP_RATES.put("GBP_USD",  new double[]{-1.8,  -0.5});
        SWAP_RATES.put("USD_JPY",  new double[]{ 5.2,  -8.5});
        SWAP_RATES.put("AUD_USD",  new double[]{ 3.8,  -6.2});
        SWAP_RATES.put("NZD_USD",  new double[]{ 4.0,  -6.5});
        SWAP_RATES.put("USDCAD",   new double[]{-2.5,  1.0});
        SWAP_RATES.put("USD_CHF",  new double[]{ 2.0,  -4.5});
        SWAP_RATES.put("GBP_JPY",  new double[]{-4.5,  0.8});
        SWAP_RATES.put("EUR_GBP",  new double[]{-1.2,  0.5});
        SWAP_RATES.put("AUD_JPY",  new double[]{ 6.5, -10.0});
        SWAP_RATES.put("NZD_JPY",  new double[]{ 7.0, -11.0});
        SWAP_RATES.put("EUR_JPY",  new double[]{ 2.0,  -4.0});
    }

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private final int emaPeriod;

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private int barsInTrade;
    private double positionSize;
    private int cooldownBars;

    public CarryPremiumStrategy(String name, String symbol) {
        this(name, symbol, EMA_PERIOD_DEFAULT);
    }

    /** Constructeur paramétré pour la robustesse paramétrique (sweep EMA). */
    public CarryPremiumStrategy(String name, String symbol, int emaPeriod) {
        this.name = name;
        this.symbol = symbol;
        this.emaPeriod = emaPeriod;
        this.positionSize = POSITION_UNITS;
    }

    public CarryPremiumStrategy() {
        this("CarryPremium", "AUD_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        // Look-ahead: garder la barre courante hors de l'historique d'indicateurs
        int minHistory = emaPeriod + 20; // EMA + marge pour ATR
        if (history.size() < minHistory - 1) {
            history.add(bar);
            return;
        }

        // 1. Indicateurs sur l'historique SANS la barre courante
        double ema = Indicators.emaLatest(history, emaPeriod);
        double atr = Indicators.atr(history, ATR_PERIOD);
        Order.Side seasonalBias = getSeasonalBias(symbol, bar.timestamp());

        // 2. Ajouter la barre courante
        history.add(bar);

        if (Double.isNaN(ema) || Double.isNaN(atr) || atr <= 0) return;

        Order.Side carryDir = carryDirection();
        if (carryDir == null) return; // pas de carry exploitable sur cette paire

        if (inTrade) {
            managePosition(bar, ema, atr);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            evaluateEntry(bar, ema, atr, carryDir, seasonalBias);
        }
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        barsInTrade = 0;
        cooldownBars = 0;
    }

    private void managePosition(Bar bar, double ema, double atr) {
        barsInTrade++;

        // Stop ATR manuel (géré ici pour que le swap soit crédité via closeOnly)
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);

        // Cassure de tendance : EMA croisée en sens inverse
        boolean trendBroken = (tradeDirection == Order.Side.BUY && bar.close() < ema)
            || (tradeDirection == Order.Side.SELL && bar.close() > ema);

        if (stopHit || trendBroken || barsInTrade >= MAX_HOLD_BARS) {
            closePosition(bar.close());
        }
    }

    private void evaluateEntry(Bar bar, double ema, double atr, Order.Side carryDir, Order.Side seasonalBias) {
        double close = bar.close();

        if (carryDir == Order.Side.BUY && close > ema && seasonalBias != Order.Side.SELL) {
            entryPrice = close;
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
        } else if (carryDir == Order.Side.SELL && close < ema && seasonalBias != Order.Side.BUY) {
            entryPrice = close;
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).asCloseOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    /** Direction du carry : BUY si long swap positif, SELL si short swap positif, null sinon. */
    private Order.Side carryDirection() {
        double[] rates = SWAP_RATES.get(symbol);
        if (rates == null) return null;
        double longSwap = rates[0];
        double shortSwap = rates[1];
        if (longSwap > 0 && longSwap > shortSwap) return Order.Side.BUY;
        if (shortSwap > 0 && shortSwap > longSwap) return Order.Side.SELL;
        return null; // GBP_USD : les deux négatifs → pas de carry
    }

    /** Inline SeasonalityFilter — mirror de SeasonalityFilter.getBias(). */
    private Order.Side getSeasonalBias(String symbol, Instant ts) {
        ZonedDateTime zdt = ts.atZone(ZoneOffset.UTC);
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();

        switch (symbol) {
            case "USDCAD":
                if (inWindow(month, day, 10, 12, 11, 26)) return Order.Side.BUY;   // 94% automne
                if (inWindow(month, day, 4, 1, 4, 30)) return Order.Side.SELL;      // 72% avril
                break;
            case "USD_JPY":
                if (inWindow(month, day, 9, 27, 11, 11)) return Order.Side.BUY;     // 88% yen faible
                break;
            case "GBP_USD":
                if (inWindow(month, day, 3, 11, 4, 25)) return Order.Side.BUY;      // 83% printemps
                if (inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;       // 89% avril
                break;
            case "EUR_USD":
                if (inWindow(month, day, 3, 16, 4, 30)) return Order.Side.BUY;      // 72% printemps
                break;
            case "AUD_USD":
                if (inWindow(month, day, 6, 4, 7, 19)) return Order.Side.BUY;       // 75% juin-juillet
                break;
            default:
                break;
        }
        return null;
    }

    private boolean inWindow(int month, int day, int sm, int sd, int em, int ed) {
        if (sm > em || (sm == em && sd > ed)) {
            // Fenêtre qui traverse le Nouvel An
            return (month > sm || (month == sm && day >= sd))
                || (month < em || (month == em && day <= ed));
        }
        return (month > sm || (month == sm && day >= sd))
            && (month < em || (month == em && day <= ed));
    }
}
