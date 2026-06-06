package com.martinfou.trading.intelligence;

import com.martinfou.trading.intelligence.ingest.IngestPipeline;
import com.martinfou.trading.intelligence.job.WeeklyPlanJob;
import com.martinfou.trading.intelligence.llm.HttpDeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CLI: ingest + DeepSeek planner/reviewer → pending/ weekly plan (Epic 22.2). */
public final class WeeklyPlanJobMain {

    private static final Logger log = LoggerFactory.getLogger(WeeklyPlanJobMain.class);

    private WeeklyPlanJobMain() {}

    public static void main(String[] args) {
        try {
            int code = run();
            System.exit(code);
        } catch (Exception ex) {
            log.error("Weekly plan job failed", ex);
            System.exit(1);
        }
    }

    static int run() throws Exception {
        var repoRoot = RepoRoots.findRepoRoot();
        var job = new WeeklyPlanJob(repoRoot, new IngestPipeline(), new HttpDeepSeekClient());
        WeeklyPlanJob.Result result = job.run();
        return switch (result.status()) {
            case APPROVED -> 0;
            case REJECTED -> 3;
            case CALENDAR_FAILED -> 2;
            case INGEST_FAILED -> 4;
            case DLQ -> 5;
            case FAILED -> 1;
        };
    }
}
