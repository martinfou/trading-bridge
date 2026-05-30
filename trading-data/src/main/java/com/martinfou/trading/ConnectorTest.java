package com.martinfou.trading;

import com.martinfou.trading.data.*;
import java.nio.file.*;

/**
 * Quick debug test for seasonality.
 */
public class ConnectorTest {
    public static void main(String[] args) throws Exception {
        // Check data dir
        String dd = System.getProperty("trading.data.dir", "NOT SET");
        System.out.println("trading.data.dir = " + dd);
        Path p = Paths.get(dd);
        System.out.println("exists: " + Files.exists(p));
        System.out.println("isDir: " + Files.isDirectory(p));
        if (Files.isDirectory(p)) {
            Files.list(p).limit(5).forEach(f -> System.out.println("  " + f.getFileName()));
        }

        // Check user.dir
        System.out.println("user.dir = " + System.getProperty("user.dir"));

        // Test seasonality
        var sa = new SeasonalityAnalyzer();
        System.out.println("\nAdages: " + sa.activeAdages());

        try {
            var profile = sa.analyze("EUR/USD");
            System.out.println("Profile: " + profile);
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
