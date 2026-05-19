package com.martinfou.trading.genetics.management;

import java.time.LocalDate;
import java.util.*;

/**
 * Registre central de toutes les strategies, leurs metadonnees et statuts.
 */
public class StrategyRegistry {
    
    public enum Status { DEV, BACKTEST, PAPER, LIVE, PAUSED, RETIRED, FAILED }
    
    public static class Entry {
        public final StrategyID id;
        public final Map<String, Object> params = new HashMap<>();
        public volatile double sharpe, profitFactor, winRate, maxDrawdown, robustness;
        public volatile Status status = Status.DEV;
        public final LocalDate created = LocalDate.now();
        public LocalDate lastOptimized = LocalDate.now();
        public String source = "genetic";
        public final List<String> tags = new ArrayList<>();
        public volatile double cumulativePnl = 0;
        
        // Multi-asset
        public final List<String> deployedSymbols = new ArrayList<>();
        public final Map<String, Double> perSymbolPnl = new HashMap<>();
        public final Map<String, Double> perSymbolSharpe = new HashMap<>();
        
        // Walk-Forward calibration
        public enum WFFrequency { DAILY, WEEKLY, MONTHLY, QUARTERLY, AFTER_20_TRADES, AFTER_100_BARS }
        public WFFrequency wfFrequency = WFFrequency.MONTHLY;
        public LocalDate lastWalkForwardDate = LocalDate.now();
        public int walkForwardISMonths = 12;
        public int walkForwardOOSWeeks = 4;
        public int tradesSinceLastWF = 0;
        public int barsSinceLastWF = 0;
        
        public boolean isWalkForwardDue() {
            if (lastWalkForwardDate == null) return true;
            return switch (wfFrequency) {
                case DAILY -> LocalDate.now().isAfter(lastWalkForwardDate);
                case WEEKLY -> LocalDate.now().minusWeeks(1).isAfter(lastWalkForwardDate);
                case MONTHLY -> LocalDate.now().minusMonths(1).isAfter(lastWalkForwardDate);
                case QUARTERLY -> LocalDate.now().minusMonths(3).isAfter(lastWalkForwardDate);
                case AFTER_20_TRADES -> tradesSinceLastWF >= 20;
                case AFTER_100_BARS -> barsSinceLastWF >= 100;
            };
        }
        
        public long daysSinceLastWF() {
            return lastWalkForwardDate == null ? 999 : 
                java.time.temporal.ChronoUnit.DAYS.between(lastWalkForwardDate, LocalDate.now());
        }
        
        public Entry(StrategyID id) { this.id = id; }
        
        public Entry withParam(String key, Object val) { params.put(key, val); return this; }
        public Entry withTag(String tag) { tags.add(tag); return this; }
        public Entry withMetrics(double sh, double pf, double wr, double dd, double rob) {
            this.sharpe = sh; this.profitFactor = pf; this.winRate = wr;
            this.maxDrawdown = dd; this.robustness = rob;
            return this;
        }
        
        public String familyColor() {
            return switch (id.family()) {
                case TR -> "\uD83D\uDD35";
                case MR -> "\uD83D\uDFE2";
                case BT -> "\uD83D\uDFE0";
                case MM -> "\uD83D\uDD34";
                case NW -> "\uD83D\uDFE3";
                case CT -> "\u26AA";
            };
        }
        
        public String statusIcon() {
            return switch (status) {
                case DEV -> "\uD83C\uDFD7\uFE0F";
                case BACKTEST -> "\uD83D\uDD0C";
                case PAPER -> "\u23F3";
                case LIVE -> "\u2705";
                case PAUSED -> "\u23F8\uFE0F";
                case RETIRED -> "\uD83D\uDCA4";
                case FAILED -> "\u274C";
            };
        }
        
        @Override public String toString() {
            String wfStatus = isWalkForwardDue() ? "\uD83D\uDD14 WF due!" : ("WF ok (" + daysSinceLastWF() + "d)");
            return String.format("%s %-14s %s  Sharpe:%.2f  PF:%.2f  Win:%.1f%%  DD:%.1f%%  Rob:%.0f  PnL:$%.0f  %s",
                familyColor(), id.shortID(), statusIcon(), sharpe, profitFactor, winRate, 
                maxDrawdown, robustness, cumulativePnl, wfStatus);
        }
    }
    
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    
    public Entry register(StrategyID id) {
        return register(id, List.of());
    }
    
    public Entry register(StrategyID id, List<String> symbols) {
        Entry e = new Entry(id);
        e.deployedSymbols.addAll(symbols);
        entries.put(id.longID(), e);
        return e;
    }
    
    public Entry get(String longID) { return entries.get(longID); }
    public Entry getByShort(String shortID) {
        return entries.values().stream()
            .filter(e -> e.id.shortID().equals(shortID))
            .findFirst().orElse(null);
    }
    
    public List<Entry> byStatus(Status status) {
        return entries.values().stream().filter(e -> e.status == status).toList();
    }
    
    public List<Entry> bySymbol(String symbol) {
        return entries.values().stream()
            .filter(e -> e.id.symbol().equals(symbol)).toList();
    }
    
    public List<Entry> byFamily(StrategyID.Family family) {
        return entries.values().stream()
            .filter(e -> e.id.family() == family).toList();
    }
    
    public List<Entry> topBySharpe(int n) {
        return entries.values().stream()
            .sorted((a, b) -> Double.compare(b.sharpe, a.sharpe))
            .limit(n).toList();
    }
    
    public List<Entry> all() { return List.copyOf(entries.values()); }
    public int count() { return entries.size(); }
    public int countByStatus(Status status) {
        return (int) entries.values().stream().filter(e -> e.status == status).count();
    }
    
    public String summary() {
        var sb = new StringBuilder();
        sb.append(String.format("\n\uD83C\uDFC6 Strategy Registry — %d strategies\n", count()));
        sb.append(String.format("  \u2705 Live: %d  \u23F3 Paper: %d  \uD83D\uDD0C Backtest: %d  \uD83C\uDFD7\uFE0F Dev: %d  \uD83D\uDCA4 Retired: %d\n",
            countByStatus(Status.LIVE), countByStatus(Status.PAPER), countByStatus(Status.BACKTEST),
            countByStatus(Status.DEV), countByStatus(Status.RETIRED)));
        sb.append("\nTop 5 by Sharpe:\n");
        var top = topBySharpe(5);
        for (int i = 0; i < top.size(); i++) {
            sb.append("  ").append(i+1).append(". ").append(top.get(i)).append("\n");
        }
        return sb.toString();
    }
}
