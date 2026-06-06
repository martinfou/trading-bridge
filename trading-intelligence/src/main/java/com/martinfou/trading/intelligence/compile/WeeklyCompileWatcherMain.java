package com.martinfou.trading.intelligence.compile;

import com.martinfou.trading.intelligence.RepoRoots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CLI: compile watcher pending/ → codegen + mvn → compiled/ (Epic 22.4). */
public final class WeeklyCompileWatcherMain {

    private static final Logger log = LoggerFactory.getLogger(WeeklyCompileWatcherMain.class);

    private WeeklyCompileWatcherMain() {}

    public static void main(String[] args) {
        try {
            int code = run();
            System.exit(code);
        } catch (Exception ex) {
            log.error("Weekly compile watcher failed", ex);
            System.exit(1);
        }
    }

    static int run() throws Exception {
        WeeklyCompileWatcher watcher = new WeeklyCompileWatcher(RepoRoots.findRepoRoot());
        WeeklyCompileWatcher.Result result = watcher.runOnce();
        return switch (result.status()) {
            case SUCCESS -> 0;
            case IDLE -> 0;
            case BUSY -> 2;
            case SKIPPED -> 3;
            case COMPILE_FAILED -> 4;
        };
    }
}
