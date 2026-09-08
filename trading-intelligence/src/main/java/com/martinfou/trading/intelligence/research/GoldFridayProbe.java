package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * Probe — structure réelle des barres XAU autour de la fermeture du vendredi
 * (convention de timestamp, barres réelles vs carry, dernière heure tradée).
 */
public class GoldFridayProbe {
    public static void main(String[] args) throws Exception {
        List<Bar> all = new ArrayList<>();
        var dir = Paths.get("data/historical/bars");
        for (int y : new int[]{2010, 2016, 2024}) {
            List<Bar> bars = HistoricalDataLoader.loadYear("XAU_USD", y, dir);
            if (bars != null) all.addAll(bars);
        }
        all.sort(Comparator.comparing(Bar::timestamp));

        boolean[] real = new boolean[all.size()];
        real[0] = true;
        for (int i = 1; i < all.size(); i++)
            real[i] = Math.abs(all.get(i).close() - all.get(i - 1).close()) > 1e-12;

        // find a Friday around 2024-06-14 and print real bars 15:00 -> end
        ZoneId UTC = ZoneId.of("UTC");
        int target = -1;
        for (int i = 0; i < all.size(); i++) {
            LocalDate d = all.get(i).timestamp().atZone(UTC).toLocalDate();
            if (d.equals(LocalDate.of(2024, 6, 14)) && real[i]) { target = i; break; }
        }
        if (target < 0) { System.out.println("no target"); return; }
        System.out.println("=== XAU_USD real bars on 2024-06-14 (Friday) ===");
        for (int i = target; i < Math.min(all.size(), target + 60); i++) {
            ZonedDateTime z = all.get(i).timestamp().atZone(UTC);
            if (!z.toLocalDate().equals(LocalDate.of(2024, 6, 14)) && !real[i]) continue;
            System.out.printf("  %s  real=%-5s close=%.2f%n", z, real[i], all.get(i).close());
            if (z.toLocalDate().isAfter(LocalDate.of(2024, 6, 14)) && !real[i] && i > target + 10) break;
        }
        System.out.println("\n=== Hour-of-day distribution of REAL bars on Fridays (2024) ===");
        Map<Integer, Integer> realByHour = new TreeMap<>();
        Map<Integer, Integer> allByHour = new TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            ZonedDateTime z = all.get(i).timestamp().atZone(UTC);
            if (z.getYear() != 2024) continue;
            if (z.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            allByHour.merge(z.getHour(), 1, Integer::sum);
            if (real[i]) realByHour.merge(z.getHour(), 1, Integer::sum);
        }
        System.out.printf("  hour: ");
        for (int h = 0; h < 24; h++) System.out.printf("%3d ", h);
        System.out.println();
        System.out.printf("  all : ");
        for (int h = 0; h < 24; h++) System.out.printf("%3d ", allByHour.getOrDefault(h, 0));
        System.out.println();
        System.out.printf("  real: ");
        for (int h = 0; h < 24; h++) System.out.printf("%3d ", realByHour.getOrDefault(h, 0));
        System.out.println();

        // Last real bar hour per Friday in 2024
        Map<LocalDate, Integer> lastRealHour = new TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            if (!real[i]) continue;
            ZonedDateTime z = all.get(i).timestamp().atZone(UTC);
            if (z.getYear() != 2024 || z.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            lastRealHour.put(z.toLocalDate(), z.getHour());
        }
        Map<Integer, Integer> hist = new TreeMap<>();
        for (int h : lastRealHour.values()) hist.merge(h, 1, Integer::sum);
        System.out.println("\nLast real Friday bar hour in 2024: " + hist);

        // also check: is the bar at hour h timestamped at the START of the window?
        // compare close of bar h with open of bar h+1 on a liquid Tuesday
        for (int i = 0; i < all.size(); i++) {
            ZonedDateTime z = all.get(i).timestamp().atZone(UTC);
            if (z.getYear() == 2024 && z.getDayOfWeek() == DayOfWeek.TUESDAY && z.getHour() == 10 && real[i] && i + 1 < all.size()) {
                Bar b = all.get(i), c = all.get(i + 1);
                System.out.printf("\nTimestamp convention probe (2024-06-11 Tue 10:00): close=%f next open=%f next ts=%s%n",
                    b.close(), c.open(), c.timestamp().atZone(UTC));
                System.out.printf("  equal => timestamp marks START of bar (close h == open h+1)%n");
                break;
            }
        }
    }
}
