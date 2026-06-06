package com.martinfou.trading.intelligence.deploy;

import com.martinfou.trading.intelligence.RepoRoots;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI: Job 3 — deploy compiled bundle to PAPER_OANDA (cron {@code scripts/weekly-deploy.sh}).
 */
public final class WeeklyDeployWatcherMain {

    private static final Logger log = LoggerFactory.getLogger(WeeklyDeployWatcherMain.class);

    private WeeklyDeployWatcherMain() {}

    public static void main(String[] args) {
        try {
            var repoRoot = RepoRoots.findRepoRoot();
            WeeklyBuilderPaths.ensureLayout(repoRoot);
            var watcher = new WeeklyDeployWatcher(repoRoot, ControlPlaneHttpClient.fromEnvironment());
            WeeklyDeployResult result = watcher.run();
            if (result.noTradeWeek()) {
                log.info("NoTradeWeek {} — bundle moved without broker runs", result.weekId());
            } else if (result.success()) {
                log.info("Deployed week {} — {} run(s)", result.weekId(), result.runIds().size());
            } else {
                log.error("Deploy failed: {}", result.error());
            }
            System.exit(result.success() ? 0 : 1);
        } catch (Exception ex) {
            log.error("Weekly deploy watcher failed", ex);
            System.exit(1);
        }
    }
}
