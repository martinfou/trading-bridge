package com.martinfou.trading.genetics.management;

/**
 * Identifiant unique d'une strategie avec nommage descriptif.
 * 
 * Format: [ORIGINE]_[FAMILLE]_[ACTIF]_[DIR]_[TF]_[ID]_v[MAJ].[MIN].[PATCH]
 * Exemple: SQ_TR_EU_L_H1_042_v2.1.0
 * Court:   TR-EU-L-042
 */
public record StrategyID(
    Origin origin,
    Family family,
    String symbol,
    Direction direction,
    String mainTimeframe,
    int number,
    Version version
) {
    public enum Origin { SQ, GEN, MAN }
    public enum Family { TR, MR, BT, MM, NW, CT }
    public enum Direction { L, S, B }
    
    public record Version(int major, int minor, int patch) {
        public static Version of(String s) {
            var parts = s.split("\\.");
            return new Version(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])
            );
        }
        @Override public String toString() { return "v" + major + "." + minor + "." + patch; }
    }
    
    /** Format long complet */
    public String longID() {
        return origin + "_" + family + "_" + symbol + "_" + direction + "_" + mainTimeframe + "_" + 
               String.format("%03d", number) + "_" + version;
    }
    
    /** Format court pour dashboard */
    public String shortID() {
        return family + "-" + symbol + "-" + direction + "-" + String.format("%03d", number);
    }
    
    public static StrategyID parse(String id) {
        // Format: SQ_TR_EU_L_H1_042_v2.1.0
        var parts = id.split("_");
        var verPart = parts[6].substring(1); // enleve le v
        return new StrategyID(
            Origin.valueOf(parts[0]),
            Family.valueOf(parts[1]),
            parts[2],
            Direction.valueOf(parts[3]),
            parts[4],
            Integer.parseInt(parts[5]),
            Version.of(verPart)
        );
    }
    
    public static StrategyID nextID(Origin origin, Family family, String symbol, Direction dir, String tf) {
        // Genere un nouvel ID avec le prochain numero disponible
        return new StrategyID(origin, family, symbol, dir, tf, 1, new Version(1, 0, 0));
    }
}
