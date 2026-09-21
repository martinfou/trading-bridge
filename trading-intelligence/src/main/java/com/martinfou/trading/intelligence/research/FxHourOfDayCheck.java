package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * FxHourOfDayCheck — Pre-validation (Pattern D) de la structure HORAIRE INTRADAY du FX.
 * Lundi 21 sept 2026 — 40e résultat. Idée NOUVELLE : jamais testée sur le FX.
 *
 * ── POURQUOI CETTE IDÉE ─────────────────────────────────────────────────────
 * Tout le pipeline FX travaille en DAILY : jour-de-semaine (37e), réversion du lundi (38e-39e),
 * fenêtres mensuelles (calendrier 4+5+8). La dimension INTRADAY n'a jamais été mesurée sur le FX —
 * seule la session du vendredi de l'OR l'a été (30e : noyau OOS-stable = Asia 00:00-08:00, la jambe
 * NY afternoon = bêta). Piste explicitement laissée ouverte le 16 sept (37e) : « décomposition horaire
 * de la session vendredi FX sur barres réelles ».
 * Question (Lundi = idée nouvelle) : OÙ DANS LA JOURNÉE le drift FX vit-il, y a-t-il un FACTEUR
 * dollar intraday, et les ancres de FLUX (fix de Londres 16:00, fin de mois) portent-elles un edge ?
 *
 * ── HYPOTHÈSES TESTÉES (mécanismes économiques, pas du data-mining) ──────────
 *  H1. Profil horaire : le drift FX n'est pas uniforme — il se concentre sur des heures précises.
 *  H2. Facteur DOLLAR intraday : certaines heures bougent les 7 paires ENSEMBLE (ordre USD),
 *      d'autres sont idiosyncratiques. Mesuré SÉPARÉMENT de la moyenne (leçon du 17 sept :
 *      un décalage de moyenne ≠ un changement de structure).
 *  H3. Fix de Londres 16:00 (WMR/Reuters) : le calcul du fix concentre des flux à 16:00 Londres
 *      (= 15:00 UTC en heure d'été, 16:00 UTC en heure d'hiver → DST-AWARE obligatoire).
 *  H4. Fix × cycle du mois : les flux de rebalancement réel (index/real money) sont concentrés
 *      en FIN DE MOIS → le fix de fin de mois devrait être plus violent que le fix de mi-mois.
 *      (≠ REJECT du « flux de fin de TRIMESTRE » du 37e : ici c'est la fenêtre INTRADAY du fix.)
 *
 * ── MÉTHODE (anti-artefacts appris) ─────────────────────────────────────────
 *  0. PROBE de convention + CALIBRATION : reproduire les mesures documentées du 39e
 *     (session lundi lun 01:00→mar 01:00 = +3.3 bp ; rendement lundi quotidien IS −2.1 bp
 *     → OOS +5.8 bp) et du 37e (vendredi quotidien négatif sur les paires risk). Si la
 *     calibration ne reproduit pas ces chiffres, le harnais est faux → rien n'est croyable.
 *  1. Barres RÉELLES uniquement (close ≠ close précédent) : les .bars sont 24/7 avec carry plat
 *     le week-end → filtre obligatoire (piège documenté les 7 et 8 sept).
 *  2. Bucket H = rendement RÉALISÉ pendant [H, H+1) UTC = close(barre@H)/close(barre
 *     réelle précédente) − 1 (convention validée par le probe : close@H ≈ open@H+1).
 *  3. Split IS 2006-2015 / OOS 2016-2026 sur CHAQUE cellule (Pattern D — obligatoire).
 *  4. Seuil de survie : 2.14 bp (coûts aller-retour) pour un hold INTRADAY (aucun rollover) ;
 *     3.4 bp pour un hold de 24 h traversant le rollover. Un edge intraday sous 2.14 bp
 *     est une statistique, pas une stratégie (Loi C du 19 sept).
 *  5. Contrôles : le rendement horaire MOYEN est comparé au drift quotidien de la paire (les
 *     heures ne sont interprétables qu'en EXCÈS du drift) ; co-mouvement mesuré séparément.
 */
public class FxHourOfDayCheck {
    private static final String BARS_DIR = "data/historical/bars";
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId LDN = ZoneId.of("Europe/London");

    /** Paires FX ; +1 = la hausse de la paire signifie DOLLAR FORT (USD_XXX), −1 sinon. */
    private static final String[] SYMS = {
        "EUR_USD", "GBP_USD", "AUD_USD", "NZD_USD", "USD_JPY", "USD_CHF", "USD_CAD"
    };

    public static void main(String[] args) throws Exception {
        Map<String, List<Bar>> data = new LinkedHashMap<>();
        for (String s : SYMS) {
            List<Bar> all = loadYears(s, 2006, 2026);
            all.sort(Comparator.comparing(Bar::timestamp));
            data.put(s, all);
            System.out.println("[load] " + s + " : " + all.size() + " barres H1  "
                + (all.isEmpty() ? "" : all.get(0).timestamp() + " -> " + all.get(all.size() - 1).timestamp()));
        }
        System.out.println();

        probeConvention(data.get("EUR_USD"));

        Map<String, HourProfile> prof = new LinkedHashMap<>();
        for (String s : SYMS) prof.put(s, profile(data.get(s)));
        HourProfile agg = new HourProfile();
        for (HourProfile p : prof.values()) agg.merge(p);

        printHourlyPerPair(prof);
        printAggregate(agg);
        calibration(data, prof);
        dollarProfile(prof, agg);
        londonFix(data);
        fixByMonthCycle(data);
        decadeDecomposition(data);
        candidateWindows(agg);
    }

    // ═══ P6 — DÉCOMPOSITION PAR DÉCENNIE des 2 anomalies candidates (Pattern D) ═══
    /**
     * Le pipeline a tué 5 « late-bloomers » : un effet présent SEULEMENT en OOS = dérive d'ère.
     * Les 2 seules cellules à cohérence trans-paires sont : (a) l'heure 20 UTC (USD faible,
     * unanimité 100 %), (b) le fix 16:00 Londres du DERNIER JOUR OUVRABLE du mois (7/7 USD fort).
     * On les décompose par décennie sur une mesure SIGNÉE DOLLAR (bp de force USD).
     */
    private static void decadeDecomposition(Map<String, List<Bar>> data) {
        System.out.println("=== P6 — DÉCOMPOSITION PAR DÉCENNIE (mesure signée DOLLAR, bp de force USD) ===");
        System.out.println("  Un effet présent seulement en 2019-2026 = late-bloomer / dérive d'ère (5 cas documentés).");
        System.out.println("  période    | n | heure 20:00 UTC (USD faible attendu NÉGATIF) | n | fix EOM-1 (USD fort attendu POSITIF) | n | fix CTRL mi-mois");
        int[] bounds = {2006, 2011, 2016, 2021, 2027};   // 4 tranches
        for (int b = 0; b < bounds.length - 1; b++) {
            int y0 = bounds[b], y1 = bounds[b + 1] - 1;
            double s20 = 0, n20 = 0, sFix = 0, nFix = 0, sCtl = 0, nCtl = 0;
            for (String s : SYMS) {
                double sign = s.startsWith("USD_") ? 1.0 : -1.0;   // +1 = hausse de la paire = USD fort
                List<Bar> bars = data.get(s);
                boolean[] real = realMask(bars);
                // dernier jour ouvrable du mois
                Map<YearMonth, List<LocalDate>> biz = new HashMap<>();
                for (Bar bar : bars) {
                    LocalDate d = bar.timestamp().atZone(UTC).toLocalDate();
                    if (d.getDayOfWeek().getValue() >= 6) continue;
                    biz.computeIfAbsent(YearMonth.from(d), k -> new ArrayList<>()).add(d);
                }
                Map<YearMonth, LocalDate> lastBiz = new HashMap<>();
                for (var e : biz.entrySet()) {
                    List<LocalDate> ds = new ArrayList<>(new TreeSet<>(e.getValue()));
                    lastBiz.put(e.getKey(), ds.get(ds.size() - 1));
                }
                for (int i = 0; i < bars.size(); i++) {
                    if (!real[i]) continue;
                    ZonedDateTime z = bars.get(i).timestamp().atZone(UTC);
                    int yr = z.getYear();
                    if (yr < y0 || yr > y1) continue;
                    double ret = sign * (bars.get(i).close() / bars.get(i).open() - 1);
                    int ldnHour = bars.get(i).timestamp().atZone(LDN).getHour();
                    if (z.getHour() == 20) { s20 += ret; n20++; }
                    if (ldnHour == 16) {
                        LocalDate d = z.toLocalDate();
                        if (d.equals(lastBiz.get(YearMonth.from(d)))) { sFix += ret; nFix++; }
                        else if (d.getDayOfMonth() >= 10 && d.getDayOfMonth() <= 20) { sCtl += ret; nCtl++; }
                    }
                }
            }
            System.out.printf("  %d-%d  |%5.0f | %+14.2f bp%22s |%5.0f | %+8.2f bp%13s |%5.0f | %+8.2f bp%n",
                y0, y1, n20, s20 / n20 * 1e4, "", nFix, sFix / nFix * 1e4, "", nCtl, sCtl / nCtl * 1e4);
        }
        System.out.println("  (rappel d'ordre de grandeur : seuil de survie 2.14 bp de coûts aller-retour)");
        System.out.println();
    }

    // ════════════════════════════ P0 — PROBE ════════════════════════════════
    private static void probeConvention(List<Bar> bars) {
        System.out.println("=== P0 — PROBE de convention (EUR_USD) ===");
        // (a) distribution of |open(i) - close(i-1)| : si les barres sont contiguës ET labellisées
        //     au DÉBUT de la fenêtre, cet écart doit être ~0 (même feed). Sinon il y a un décalage.
        int[] buckets = new int[5];
        double maxDev = 0;
        int n = 0;
        for (int i = 1; i < bars.size(); i++) {
            if (Math.abs(bars.get(i).close() - bars.get(i - 1).close()) < 1e-12) continue; // barre plate
            double dev = Math.abs(bars.get(i).open() - bars.get(i - 1).close()) / bars.get(i - 1).close();
            maxDev = Math.max(maxDev, dev);
            double pips = dev * 1e4;
            buckets[pips < 0.05 ? 0 : pips < 0.5 ? 1 : pips < 2 ? 2 : pips < 10 ? 3 : 4]++;
            n++;
        }
        System.out.printf("  |open(i)-close(i-1)| sur %d transitions réelles : <0.05 pip %d | 0.05-0.5 %d | 0.5-2 %d | 2-10 %d | >10 %d%n",
            n, buckets[0], buckets[1], buckets[2], buckets[3], buckets[4]);
        System.out.printf("  écart max %.2e (%.1f pips)  => écart nul quasi partout = barres contiguës%n", maxDev, maxDev * 1e4);
        // (b) frontière de semaine : la 1re barre réelle du dimanche EST l'ouverture du marché
        boolean[] real = realMask(bars);
        int shown = 0;
        for (int i = 1; i < bars.size() && shown < 3; i++) {
            if (!real[i]) continue;
            ZonedDateTime z = bars.get(i).timestamp().atZone(UTC);
            ZonedDateTime zp = bars.get(i - 1).timestamp().atZone(UTC);
            if (z.getDayOfWeek() == DayOfWeek.SUNDAY && zp.getDayOfWeek() != DayOfWeek.SUNDAY) {
                System.out.printf("  ouverture semaine %s : dernière barre réelle %s (close %.5f) -> 1re barre dim %s (open %.5f close %.5f)%n",
                    z.toLocalDate(), zp.toLocalDateTime(), bars.get(i - 1).close(),
                    z.toLocalDateTime(), bars.get(i).open(), bars.get(i).close());
                shown++;
            }
        }
        // (c) échantillon brut de 8 barres réelles consécutives (pour voir la structure OHLC)
        System.out.println("  8 barres réelles consécutives (timestamp | O H L C) :");
        int c = 0;
        for (int i = 1; i < bars.size() && c < 8; i++) {
            if (!real[i]) continue;
            Bar b = bars.get(i);
            System.out.printf("    %s | %.5f %.5f %.5f %.5f%n", b.timestamp().atZone(UTC).toLocalDateTime(),
                b.open(), b.high(), b.low(), b.close());
            c++;
        }
        System.out.println("  (lecture : si le timestamp est le DÉBUT de la fenêtre, la barre @H couvre [H, H+1))");
        System.out.println();

        // (d) STRUCTURE DES BARRES DE CARRY : le filtre documenté « close != close précédent » est-il
        //     suffisant ? Une barre de carry est un placeholder — mesurons plat (H==L) vs close==prevclose.
        System.out.println("  --- (d) anatomie des barres par jour de semaine (EUR_USD) ---");
        System.out.println("  jour |   n bars | H==L (fully flat) | close==prevClose | les DEUX");
        Map<DayOfWeek, int[]> cnt = new TreeMap<>();
        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            DayOfWeek dw = b.timestamp().atZone(UTC).getDayOfWeek();
            int[] a = cnt.computeIfAbsent(dw, k -> new int[4]);
            boolean flat = Math.abs(b.high() - b.low()) < 1e-12;
            boolean eqPrev = i > 0 && Math.abs(b.close() - bars.get(i - 1).close()) < 1e-12;
            a[0]++;
            if (flat) a[1]++;
            if (eqPrev) a[2]++;
            if (flat && eqPrev) a[3]++;
        }
        for (var e : cnt.entrySet()) {
            int[] a = e.getValue();
            System.out.printf("  %-6s| %8d | %17d | %17d | %8d%n", e.getKey(), a[0], a[1], a[2], a[3]);
        }
        // (e) dump d'un weekend complet
        System.out.println("  --- (e) weekend 2024-01-12 (ven) -> 2024-01-15 (lun) : toutes les barres ---");
        System.out.println("      timestamp | O H L C | flat(H==L) | close==prev");
        int shown2 = 0;
        for (int i = 0; i < bars.size(); i++) {
            ZonedDateTime z = bars.get(i).timestamp().atZone(UTC);
            if (z.getYear() != 2024) continue;
            if (z.getMonthValue() != 1) continue;
            if (z.getDayOfMonth() < 12 || z.getDayOfMonth() > 15) continue;
            Bar b = bars.get(i);
            boolean flat = Math.abs(b.high() - b.low()) < 1e-12;
            boolean eqPrev = i > 0 && Math.abs(b.close() - bars.get(i - 1).close()) < 1e-12;
            System.out.printf("      %s | %.5f %.5f %.5f %.5f | %-4s | %s%n",
                z.toLocalDateTime(), b.open(), b.high(), b.low(), b.close(), flat, eqPrev);
            if (++shown2 > 40) { System.out.println("      ... (tronqué)"); break; }
        }
        System.out.println();
    }

    // ════════════════════════ PROFIL HORAIRE ════════════════════════════════
    /**
     * a[0]=sum, a[1]=n, a[2]=sumSq, a[3]=sumIS, a[4]=nIS, a[5]=sumOOS, a[6]=nOOS,
     * a[7]=nPos, a[8]=nPosIS, a[9]=nPosOOS
     */
    private static class HourProfile {
        final Map<Integer, double[]> all = new TreeMap<>();

        void merge(HourProfile o) {
            for (var e : o.all.entrySet()) {
                double[] a = all.computeIfAbsent(e.getKey(), k -> new double[10]);
                for (int i = 0; i < 10; i++) a[i] += e.getValue()[i];
            }
        }
        static double avg(double[] a, int si, int ni) { return a[ni] == 0 ? Double.NaN : a[si] / a[ni]; }
        static double hitPct(double[] a, int ni, int pi) { return a[ni] == 0 ? Double.NaN : 100.0 * a[pi] / a[ni]; }
        static double se(double[] a) {
            if (a[1] < 2) return Double.NaN;
            double m = a[0] / a[1];
            double var = Math.max(0, (a[2] - a[1] * m * m) / (a[1] - 1));
            return Math.sqrt(var / a[1]);
        }
    }

    private static HourProfile profile(List<Bar> bars) {
        boolean[] real = realMask(bars);
        HourProfile hp = new HourProfile();
        for (int i = 0; i < bars.size(); i++) {
            if (!real[i]) continue;
            ZonedDateTime z = bars.get(i).timestamp().atZone(UTC);
            // rendement INTRABAR = close/open - 1 : mesure le mouvement DE L'HEURE, sans contamination
            // par le gap de week-end ni par les glitches de transition (open(i) ≈ close(i-1) à <0.5 pip).
            double ret = bars.get(i).close() / bars.get(i).open() - 1;
            double[] a = hp.all.computeIfAbsent(z.getHour(), k -> new double[10]);
            boolean is = z.getYear() <= 2015;
            a[0] += ret; a[1]++; a[2] += ret * ret;
            if (ret > 0) a[7]++;
            if (is) { a[3] += ret; a[4]++; if (ret > 0) a[8]++; }
            else { a[5] += ret; a[6]++; if (ret > 0) a[9]++; }
        }
        return hp;
    }

    private static boolean[] realMask(List<Bar> bars) {
        // ♻️ CORRECTION DU FILTRE DOCUMENTÉ (21 sept 2026) : « close != close précédent » laisse
        // passer les barres de carry dont le prix diffère du dernier close réel (artefact de spread,
        // ~15-40 barres par paire sur 20 ans, concentrées en fin de vendredi / sam-dim 00:00).
        // Définition STRICTE mesurée au probe (d) : une barre de carry est PLATE (high == low).
        // Réel = high > low. Vérifié : 100 % des samedis, 89 % des dimanches, 11 % des vendredis.
        boolean[] real = new boolean[bars.size()];
        for (int i = 0; i < bars.size(); i++)
            real[i] = bars.get(i).high() - bars.get(i).low() > 1e-12;
        return real;
    }

    private static void printHourlyPerPair(Map<String, HourProfile> prof) {
        System.out.println("=== P1 — PROFIL HORAIRE PAR PAIRE (bp = 0.01 %) — rendement réalisé pendant [H,H+1) UTC ===");
        for (var e : prof.entrySet()) {
            HourProfile hp = e.getValue();
            System.out.println("--- " + e.getKey() + " ---");
            System.out.println("   H |     n |  avg bp | hit%  |    t  |   IS bp (n)   |  OOS bp (n)   |");
            for (var h : hp.all.entrySet()) {
                double[] a = h.getValue();
                double avg = HourProfile.avg(a, 0, 1) * 1e4;
                double se = HourProfile.se(a) * 1e4;
                double t = Double.isNaN(se) || se == 0 ? 0 : avg / se;
                double is = HourProfile.avg(a, 3, 4) * 1e4, oos = HourProfile.avg(a, 5, 6) * 1e4;
                System.out.printf("  %2d | %5.0f | %+7.2f | %4.0f%% | %+5.2f | %+7.2f (%4.0f) | %+7.2f (%4.0f) |%n",
                    h.getKey(), a[1], avg, HourProfile.hitPct(a, 1, 7), t, is, a[4], oos, a[6]);
            }
            double s = 0, n = 0;
            for (double[] a : hp.all.values()) { s += a[0]; n += a[1]; }
            System.out.printf("  DRIFT QUOTIDIEN (toutes heures) : %+.2f bp  (n=%.0f)%n%n", s / n * 1e4, n);
        }
    }

    private static void printAggregate(HourProfile agg) {
        System.out.println("=== P1bis — AGRÉGAT des 7 PAIRES, signe DOLLAR (+ = USD fort) ===");
        System.out.println("   H | DOLLAR bp |  IS bp  | OOS bp  | lecture");
        for (var h : agg.all.entrySet()) {
            double[] a = h.getValue();
            double avg = HourProfile.avg(a, 0, 1) * 1e4;
            double is = HourProfile.avg(a, 3, 4) * 1e4, oos = HourProfile.avg(a, 5, 6) * 1e4;
            String verdict = (is > 0 && oos > 0) ? "USD fort (stable)"
                : (is < 0 && oos < 0) ? "USD faible (stable)" : "INSTABLE (IS/OOS divergent)";
            System.out.printf("  %2d | %+9.2f | %+7.2f | %+7.2f | %s%n", h.getKey(), avg, is, oos, verdict);
        }
        System.out.println();
    }

    // ══════ CALIBRATION — reproduire les mesures documentées (37e, 39e) ══════
    private static void calibration(Map<String, List<Bar>> data, Map<String, HourProfile> prof) {
        System.out.println("=== CALIBRATION — reproduction des mesures documentées ===");
        System.out.println("  (A) rendement QUOTIDIEN par jour-de-semaine (bp), IS/OOS — attendu : vendredi NÉGATIF sur");
        System.out.println("      les paires risk (37e : EUR -0.032%, GBP -0.054%, AUD -0.043%, NZD -0.035%) ;");
        System.out.println("      lundi inconditionnel pool : IS -2.1 bp -> OOS +5.8 bp (39e).");

        // (A) daily returns by weekday
        Map<String, Map<DayOfWeek, double[]>> byPair = new LinkedHashMap<>(); // dow -> [sum,n,sumIS,nIS,sumOOS,nOOS]
        for (String s : SYMS) {
            List<Bar> bars = data.get(s);
            boolean[] real = realMask(bars);
            // last real close per date
            Map<LocalDate, Double> lastClose = new TreeMap<>();
            for (int i = 0; i < bars.size(); i++) {
                if (!real[i]) continue;
                lastClose.put(bars.get(i).timestamp().atZone(UTC).toLocalDate(), bars.get(i).close());
            }
            Map<DayOfWeek, double[]> acc = new LinkedHashMap<>();
            LocalDate prev = null;
            for (var e : lastClose.entrySet()) {
                if (prev != null) {
                    double ret = e.getValue() / lastClose.get(prev) - 1;
                    DayOfWeek dw = e.getKey().getDayOfWeek();
                    double[] a = acc.computeIfAbsent(dw, k -> new double[6]);
                    boolean is = e.getKey().getYear() <= 2015;
                    a[0] += ret; a[1]++;
                    if (is) { a[2] += ret; a[3]++; } else { a[4] += ret; a[5]++; }
                }
                prev = e.getKey();
            }
            byPair.put(s, acc);
        }
        System.out.println("  paire    |     LUN    |     MAR    |     MER    |     JEU    |     VEN    |");
        for (var e : byPair.entrySet()) {
            StringBuilder sb = new StringBuilder(String.format("  %-8s |", e.getKey()));
            for (DayOfWeek dw : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
                double[] a = e.getValue().get(dw);
                sb.append(a == null ? String.format(" %+10s |", "-") : String.format(" %+9.2f |", a[0] / a[1] * 1e4));
            }
            System.out.println(sb);
        }
        // pool IS/OOS for Monday
        double sMon = 0, nMon = 0, sMonIS = 0, nMonIS = 0, sMonOOS = 0, nMonOOS = 0;
        for (var e : byPair.values()) {
            double[] a = e.get(DayOfWeek.MONDAY);
            if (a == null) continue;
            sMon += a[0]; nMon += a[1]; sMonIS += a[2]; nMonIS += a[3]; sMonOOS += a[4]; nMonOOS += a[5];
        }
        System.out.printf("  POOL LUNDI : %+.2f bp (n=%.0f) | IS %+.2f bp (n=%.0f) | OOS %+.2f bp (n=%.0f)%n",
            sMon / nMon * 1e4, nMon, sMonIS / nMonIS * 1e4, nMonIS, sMonOOS / nMonOOS * 1e4, nMonOOS);

        // (B) Monday SESSION 01:00 -> next Tuesday 01:00 (definition of the 39e)
        System.out.println("  (B) SESSION lundi lun 01:00 -> mar 01:00 UTC (définition du 39e : attendu ≈ +3.3 bp) :");
        double ss = 0, nn = 0;
        for (String s : SYMS) {
            List<Bar> bars = data.get(s);
            boolean[] real = realMask(bars);
            double sum = 0; int cnt = 0;
            for (int i = 0; i < bars.size(); i++) {
                if (!real[i]) continue;
                ZonedDateTime z = bars.get(i).timestamp().atZone(UTC);
                if (z.getDayOfWeek() != DayOfWeek.MONDAY || z.getHour() != 1) continue;
                // next real bar at Tuesday 01:00
                for (int j = i + 1; j < bars.size(); j++) {
                    if (!real[j]) continue;
                    ZonedDateTime z2 = bars.get(j).timestamp().atZone(UTC);
                    if (z2.toLocalDate().isAfter(z.toLocalDate().plusDays(1))) break;
                    if (z2.toLocalDate().equals(z.toLocalDate().plusDays(1)) && z2.getHour() == 1) {
                        sum += bars.get(j).close() / bars.get(i).close() - 1;
                        cnt++;
                        break;
                    }
                }
            }
            System.out.printf("      %-8s n=%3d  avg %+6.2f bp%n", s, cnt, cnt == 0 ? 0 : sum / cnt * 1e4);
            ss += sum; nn += cnt;
        }
        System.out.printf("      POOL 8/7 paires : %+.2f bp (n=%.0f)%n%n", ss / nn * 1e4, nn);
    }

    // ═══════════ P2 — FACTEUR DOLLAR : MOYENNE vs CO-MOUVEMENT ═════════════
    private static void dollarProfile(Map<String, HourProfile> prof, HourProfile agg) {
        System.out.println("=== P2 — FACTEUR DOLLAR INTRADAY : moyenne vs CO-MOUVEMENT par heure ===");
        System.out.println("  Leçon du 17 sept : un facteur de POSITIONNEMENT décale les MOYENNES sans élever les corrélations.");
        System.out.println("  ici : part des 7 paires dont le signe horaire suit l'agrégat dollar (50% = idiosyncratique).");
        System.out.println("   H | dollar bp | accord% | lecture");
        for (int h = 0; h < 24; h++) {
            double[] aggA = agg.all.get(h);
            if (aggA == null) continue;
            double aggMean = aggA[0] / aggA[1];
            int agree = 0, n = 0;
            for (var e : prof.entrySet()) {
                double[] a = e.getValue().all.get(h);
                if (a == null) continue;
                n++;
                if (Math.signum(a[0] / a[1]) == Math.signum(aggMean)) agree++;
            }
            double pct = 100.0 * agree / n;
            String lecture = pct >= 85 ? "FACTEUR (unanimité)" : pct >= 71 ? "facteur modéré" : "idiosyncratique";
            System.out.printf("  %2d | %+9.2f | %5.0f%%  | %s%n", h, aggMean * 1e4, pct, lecture);
        }
        System.out.println();
    }

    // ═══════════════ P3 — FIX DE LONDRES 16:00 (DST-AWARE) ═════════════════
    private static void londonFix(Map<String, List<Bar>> data) {
        System.out.println("=== P3 — FIX DE LONDRES 16:00 (WMR/Reuters) — DST-AWARE ===");
        System.out.println("  16:00 Londres = 15:00 UTC (BST) ou 16:00 UTC (GMT). Barre dont l'heure LOCALE Europe/London = 16.");
        System.out.println("  paire    |   n  | fix bp | fix-1 bp | fix+1 bp | IS fix | OOS fix | fix − drift jour |");
        for (String s : SYMS) {
            List<Bar> bars = data.get(s);
            boolean[] real = realMask(bars);
            List<Double> fix = new ArrayList<>(), fm1 = new ArrayList<>(), fp1 = new ArrayList<>();
            List<Double> fixIS = new ArrayList<>(), fixOOS = new ArrayList<>(), allReal = new ArrayList<>();
            for (int i = 0; i < bars.size(); i++) {
                if (!real[i]) continue;
                ZonedDateTime z = bars.get(i).timestamp().atZone(UTC);
                ZonedDateTime l = bars.get(i).timestamp().atZone(LDN);
                double ret = bars.get(i).close() / bars.get(i).open() - 1;
                allReal.add(ret);
                if (l.getHour() == 16) {
                    fix.add(ret);
                    if (z.getYear() <= 2015) fixIS.add(ret); else fixOOS.add(ret);
                } else if (l.getHour() == 15) fm1.add(ret);
                else if (l.getHour() == 17) fp1.add(ret);
            }
            System.out.printf("  %-8s | %4d | %+6.2f | %+8.2f | %+8.2f | %+6.2f | %+7.2f | %+16.2f |%n",
                s, fix.size(), mean(fix) * 1e4, mean(fm1) * 1e4, mean(fp1) * 1e4,
                mean(fixIS) * 1e4, mean(fixOOS) * 1e4, (mean(fix) - mean(allReal)) * 1e4);
        }
        System.out.println();
    }

    // ═════════════ P4 — FIX × CYCLE DU MOIS (fin de mois vs mi-mois) ════════
    private static void fixByMonthCycle(Map<String, List<Bar>> data) {
        System.out.println("=== P4 — FIX 16:00 LONDRES × CYCLE DU MOIS ===");
        System.out.println("  EOM-1 = dernier jour ouvrable du mois ; EOM-3 = 3 derniers jours ouvrables ;");
        System.out.println("  CTRL = jours 10-20 du mois ; QE = dernier jour ouvrable du trimestre.");
        System.out.println("  paire    | n EOM1 | EOM1 bp |  IS   |  OOS  | n EOM3 | EOM3 bp | CTRL bp |  QE bp |");
        for (String s : SYMS) {
            List<Bar> bars = data.get(s);
            boolean[] real = realMask(bars);
            Map<YearMonth, List<LocalDate>> bizByMonth = new HashMap<>();
            for (Bar b : bars) {
                LocalDate d = b.timestamp().atZone(UTC).toLocalDate();
                if (d.getDayOfWeek().getValue() >= 6) continue;
                bizByMonth.computeIfAbsent(YearMonth.from(d), k -> new ArrayList<>()).add(d);
            }
            Map<YearMonth, LocalDate> lastBiz = new HashMap<>();
            Set<LocalDate> tail3 = new HashSet<>();
            for (var e : bizByMonth.entrySet()) {
                List<LocalDate> ds = new ArrayList<>(new TreeSet<>(e.getValue()));
                LocalDate last = ds.get(ds.size() - 1);
                lastBiz.put(e.getKey(), last);
                for (int i = Math.max(0, ds.size() - 3); i < ds.size(); i++) tail3.add(ds.get(i));
            }
            List<Double> eom1 = new ArrayList<>(), eom1IS = new ArrayList<>(), eom1OOS = new ArrayList<>();
            List<Double> eom3 = new ArrayList<>(), ctrl = new ArrayList<>(), qe = new ArrayList<>();
            for (int i = 0; i < bars.size(); i++) {
                if (!real[i]) continue;
                ZonedDateTime z = bars.get(i).timestamp().atZone(UTC);
                if (bars.get(i).timestamp().atZone(LDN).getHour() != 16) continue;
                LocalDate d = z.toLocalDate();
                double ret = bars.get(i).close() / bars.get(i).open() - 1;
                if (tail3.contains(d)) eom3.add(ret);
                if (d.equals(lastBiz.get(YearMonth.from(d)))) {
                    eom1.add(ret);
                    if (z.getYear() <= 2015) eom1IS.add(ret); else eom1OOS.add(ret);
                    if (d.getMonthValue() % 3 == 0) qe.add(ret);
                } else if (d.getDayOfMonth() >= 10 && d.getDayOfMonth() <= 20) ctrl.add(ret);
            }
            System.out.printf("  %-8s | %6d | %+7.2f | %+5.2f | %+5.2f | %6d | %+7.2f | %+7.2f | %+6.2f |%n",
                s, eom1.size(), mean(eom1) * 1e4, mean(eom1IS) * 1e4, mean(eom1OOS) * 1e4,
                eom3.size(), mean(eom3) * 1e4, mean(ctrl) * 1e4, mean(qe) * 1e4);
        }
        System.out.println();
    }

    // ═══════ P5 — FENÊTRES CANDIDATES vs SEUIL DE COÛTS (2.14 bp) ══════════
    private static void candidateWindows(HourProfile agg) {
        System.out.println("=== P5 — FENÊTRES CANDIDATES (agrégat dollar) vs SEUIL DE SURVIE ===");
        System.out.println("  Seuil intraday = 2.14 bp (coûts aller-retour, aucun rollover traversé).");
        System.out.println("  fenêtre                |     n   |  avg bp |  IS bp  | OOS bp  | >2.14 bp ? | même signe IS/OOS ?");
        int[][] wins = {{0, 7}, {7, 12}, {12, 16}, {16, 21}, {21, 24}, {0, 8}, {13, 14}, {15, 16}, {16, 17}, {8, 12}};
        String[] names = {"Asia 00-07", "London 07-12", "Overlap 12-16", "NY 16-21", "Late 21-24",
            "Asia/early 00-08", "US data 13-14", "UTC-adj 15-16", "UTC-adj 16-17", "London 08-12"};
        for (int w = 0; w < wins.length; w++) {
            double s = 0, n = 0, sIS = 0, nIS = 0, sOOS = 0, nOOS = 0;
            for (int h = wins[w][0]; h < wins[w][1]; h++) {
                double[] a = agg.all.get(h);
                if (a == null) continue;
                s += a[0]; n += a[1]; sIS += a[3]; nIS += a[4]; sOOS += a[5]; nOOS += a[6];
            }
            double avg = s / n * 1e4, is = sIS / nIS * 1e4, oos = sOOS / nOOS * 1e4;
            System.out.printf("  %-22s | %7.0f | %+7.2f | %+7.2f | %+7.2f | %-10s | %s%n",
                names[w], n, avg, is, oos, Math.abs(avg) > 2.14 ? "OUI" : "non",
                Math.signum(is) == Math.signum(oos) ? "OUI" : "NON");
        }
        System.out.println();
        System.out.println("  ⚠️ Même signe IS/OOS n'est PAS une preuve d'edge : les 4 cellules du 39e l'étaient en OOS");
        System.out.println("     et c'était une dérive d'ère. Croiser avec le contrôle de dérive et le co-mouvement.");
    }

    // ──────────────────────────── utilitaires ───────────────────────────────
    private static List<Bar> loadYears(String symbol, int from, int to) throws Exception {
        List<Bar> all = new ArrayList<>();
        var barsDir = Paths.get(BARS_DIR);
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars != null && !bars.isEmpty()) all.addAll(bars);
            } catch (Exception e) { /* année absente */ }
        }
        return all;
    }

    private static double mean(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().mapToDouble(d -> d).average().orElse(0);
    }
}
