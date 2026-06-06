# Investigation: Echec de chargement des barres de données historiques (Failed to load bars)

## Hand-off Brief

1. **What happened.** L'API `POST /api/runs` a renvoyé une erreur `400: {"error":"Failed to load bars for run ...` lors de l'exécution d'un backtest en raison de l'absence des données de barres historiques locales demandées (probablement pour EUR_USD 2012) et de la non-recompilation/reconstruction du projet qui a empêché le bon fonctionnement du mécanisme de téléchargement automatique.
2. **Where the case stands.** La classe `RunManager.java` a été récemment modifiée pour ajouter le téléchargement automatique des barres manquantes en exécutant `./scripts/download-data.sh` en mode synchrone, mais ces modifications n'ont pas été recompilées dans le JAR shaded utilisé par la plateforme runtime.
3. **What's needed next.** Compiler le projet avec `mvn clean install` depuis la racine pour s'assurer que les classes compilées et le JAR shaded soient à jour, puis relancer le serveur de la control plane pour tester à nouveau.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-06-06                                                                 |
| Status           | Active                                                                     |
| System           | Linux, Java 21, Maven 4.x                                                  |
| Evidence sources | Code source, historique git status, logs Maven                             |

## Problem Statement

L'utilisateur a lancé un backtest et a obtenu l'erreur :
`OST /api/runs 400: {"error":"Failed to load bars for run a0679d77-67cd-4d`
L'utilisateur indique ne pas avoir recompilé l'ensemble du projet avant de tester.

## Evidence Inventory

| Source   | Status                          | Notes     |
| -------- | ------------------------------- | --------- |
| Git status / diff | Available | Indique des modifications locales non recompilées dans `RunManager.java` et `ControlPlaneServerTest.java`. |
| Répertoire des données | Available | `data/historical/bars` ne contient que 2025/2026. L'année de test standard (2012) est absente. |
| Code source | Available | `RunManager.java` ligne 219 et `ControlPlaneServer.java` gèrent et renvoient cette erreur. |

## Investigation Backlog

| # | Path to Explore | Priority              | Status                                | Notes     |
| - | --------------- | --------------------- | ------------------------------------- | --------- |
| 1 | Recompiler et relancer le projet | High | Open | Vérifier si la recompilation résout le problème de téléchargement automatique et de chargement. |
| 2 | Vérifier l'exécution du script de téléchargement | Medium | Open | Vérifier si `./scripts/download-data.sh` fonctionne correctement quand les classes sont à jour. |

## Timeline of Events

| Time        | Event               | Source                | Confidence            |
| ----------- | ------------------- | --------------------- | --------------------- |
| 2026-06-06  | Erreur HTTP 400 sur `POST /api/runs` | User report | Confirmed |
| 2026-06-06  | Analyse de `git status` montrant des fichiers modifiés et non recompilés | Git tool | Confirmed |

## Confirmed Findings

### Finding 1: Absence de données locales pour l'année du backtest

**Evidence:** `data/historical/bars` et `data/historical/dukascopy` ne contiennent que les fichiers de 2025 et 2026.

**Detail:** Toute requête de backtest sur une autre année (comme 2012 requise par le test `GoldenBacktestTest` ou d'autres configurations par défaut) échouera si le téléchargement automatique n'est pas actif ou échoue.

### Finding 2: Code de téléchargement automatique non compilé

**Evidence:** `git diff` montre des modifications récentes dans `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java` introduisent `lockAndDownload` et `downloadYearSync` via le script `./scripts/download-data.sh`.

**Detail:** Le script `./scripts/download-data.sh` s'appuie sur le classpath `trading-data/target/classes:trading-core/target/classes` ou sur le jar shaded de runtime. Si le projet n'est pas recompilé, le convertisseur Java `BarStore` appelé par le script échouera par manque de classes à jour, empêchant la création du fichier `.bars` final même si le CSV brut est récupéré.

## Deduced Conclusions

### Deduction 1: Nécessité d'une recompilation globale

**Based on:** Finding 1 et Finding 2.

**Reasoning:** Le script `./scripts/download-data.sh` tente de lancer la classe `com.martinfou.trading.data.BarStore` avec `java -cp ...`. Si le projet n'est pas recompilé (`mvn clean install`), soit le classpath est vide, soit il contient d'anciennes versions incompatibles, ce qui cause l'échec silencieux (ou bruyant) de la conversion et donc l'absence finale de barres pour le run.

**Conclusion:** Une recompilation complète via `mvn clean install` à la racine est nécessaire pour mettre à jour les classes et le JAR de la control plane avant de démarrer l'application Electron ou le serveur Java.

## Hypothesized Paths

### Hypothesis 1: Echec du script de téléchargement dû au classpath Java

**Status:** Open

**Theory:** Le script `./scripts/download-data.sh` échoue lors de l'appel à `java -cp ... com.martinfou.trading.data.BarStore` car le projet n'est pas compilé.

**Would confirm:** Lancer le script de téléchargement manuellement et observer une erreur `ClassNotFoundException` ou similaire.

**Would refute:** Le script fonctionne parfaitement sans compilation (très improbable).

## Missing Evidence

| Gap              | Impact                               | How to Obtain   |
| ---------------- | ------------------------------------ | --------------- |
| Logs d'exécution de `./scripts/download-data.sh` lors du run | Confirmer pourquoi le téléchargement a échoué | Exécuter manuellement ou examiner les logs système/console si disponibles |

## Source Code Trace

| Element       | Detail                                      |
| ------------- | ------------------------------------------- |
| Error origin  | `com.martinfou.trading.runtime.RunManager.java:219` (dans `start`) |
| Trigger       | Appel HTTP `POST /api/runs` avec une configuration de barres historique |
| Condition     | `loadBars(config)` lève une `IOException` car le fichier de barres n'est pas trouvé et n'a pas pu être généré |
| Related files | `com.martinfou.trading.runtime.BarSourceResolver.java` |

## Conclusion

**Confidence:** High

Le problème provient de la non-compilation du projet après des modifications substantielles dans la gestion du chargement et du téléchargement automatique des barres dans `RunManager.java`. Sans recompilation complète, le runtime de l'application (ou le JAR utilisé par Electron) n'intègre pas le nouveau code ou ne parvient pas à exécuter les classes nécessaires à la conversion des données téléchargées.

## Recommended Next Steps

### Fix direction

Exécuter une compilation et installation Maven complète :
`mvn clean install` depuis la racine du dépôt.

Si vous testez via l'application bureau (Electron), assurez-vous de recréer le bundle Java pour le processus principal d'Electron :
```bash
# Depuis la racine
mvn package -pl trading-runtime -am -DskipTests
cp trading-runtime/target/*-shaded.jar desktop/desktop-resources/jar/control-plane.jar
```
Puis relancez l'application desktop.
