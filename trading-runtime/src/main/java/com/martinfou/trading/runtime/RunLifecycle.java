package com.martinfou.trading.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Run lifecycle orchestration (register → start → stop/pause/resume → archive).
 * Promote gates, gap detection, and deployment persistence live in separate services.
 */
public interface RunLifecycle {

    RunRecord register(RunConfigSnapshot config);

    RunRecord start(String runId);

    RunRecord stop(String runId);

    RunRecord pause(String runId);

    RunRecord resume(String runId);

    RunRecord archive(String runId);

    Optional<RunRecord> get(String runId);

    /** @param filter null returns all runs */
    List<RunRecord> list(RunRecord.Status filter);

    void addTransitionListener(RunTransitionListener listener);
}
