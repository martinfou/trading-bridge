package com.martinfou.trading.strategies;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Règle de la lane P&L IBKR : un ordre envoyé à IBKR doit porter `orderRef` = nom de la stratégie.
 *
 * Sans lui, le fill ne peut pas être rattaché à une stratégie : il entre dans le miroir comme un leak, et un
 * backtest prometteur devient indistinguable d'un clic manuel. La revue du publisher a montré que rien ne
 * l'imposait — le client IB Gateway plaçait des ordres sans jamais remplir orderRef.
 *
 * Ce test balaie les sources de production : tout fichier qui appelle `placeOrder` sur un chemin IBKR doit
 * aussi mentionner `orderRef`. Il échoue dès qu'un nouveau chemin d'ordre IBKR apparaît sans le champ.
 */
class IbkrOrderRefGateTest {

    private static final List<String> MODULES = List.of(
            "trading-data", "trading-broker", "trading-runtime", "trading-strategies", "trading-core",
            "trading-backtest", "trading-intelligence", "trading-genetics", "trading-parser", "trading-examples",
            "trading-tui");

    @Test
    void everyIbkrOrderPathStampsTheOrderRef() throws IOException {
        Path root = repositoryRoot();
        List<Path> ibkrOrderPaths = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        for (String module : MODULES) {
            Path sources = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sources)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(file);
                    if (!content.contains("placeOrder")) {
                        continue;
                    }
                    String path = file.toString().replace('\\', '/');
                    // L'API IB elle-meme est vendorisee sous com/ib : son placeOrder est de la plomberie,
                    // pas un de nos chemins d'ordre.
                    if (path.contains("/com/ib/")) {
                        continue;
                    }
                    boolean ibkr = path.toLowerCase().contains("ibkr") || content.contains("com.ib.client");
                    if (!ibkr) {
                        continue;
                    }
                    ibkrOrderPaths.add(file);
                    // Un appel reel, pas le mot dans un commentaire : c'est comme ca qu'un orderRef
                    // jamais rempli a pu passer.
                    if (!content.contains(".orderRef(")) {
                        offenders.add(root.relativize(file).toString());
                    }
                }
            }
        }

        // Le chemin IB Gateway existe aujourd'hui : si le balayage n'en trouve aucun, c'est le test qui est cassé.
        assertFalse(ibkrOrderPaths.isEmpty(),
                "aucun chemin d'ordre IBKR trouvé : le balayage ne couvre plus les sources, corriger le test");
        assertTrue(offenders.isEmpty(),
                "chemin(s) d'ordre IBKR sans orderRef (la stratégie ne pourra pas être attribuée) : " + offenders);
    }

    private Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve(".git"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("racine du dépôt introuvable depuis " + Paths.get("").toAbsolutePath());
        }
        return current;
    }
}
