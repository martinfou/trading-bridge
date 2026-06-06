package com.martinfou.trading.intelligence;

import com.martinfou.trading.intelligence.brief.IngestStatus;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBriefIO;
import com.martinfou.trading.intelligence.ingest.CalendarIngestException;
import com.martinfou.trading.intelligence.ingest.IngestPipeline;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * CLI: ingest weekly intel and write {@code data/weekly-intel/brief-YYYY-MM-DD.json}.
 *
 * <p>Manual debug aggregate: {@link com.martinfou.trading.data.WeeklyAnalysisRunner}
 */
public final class WeeklyIntelIngestMain {

    private static final Logger log = LoggerFactory.getLogger(WeeklyIntelIngestMain.class);

    private WeeklyIntelIngestMain() {}

    public static void main(String[] args) {
        try {
            int code = run();
            System.exit(code);
        } catch (Exception ex) {
            log.error("Weekly intel ingest failed", ex);
            System.exit(1);
        }
    }

    public static int run() throws Exception {
        Path repoRoot = RepoRoots.findRepoRoot();
        WeeklyBuilderPaths.ensureLayout(repoRoot);

        IngestPipeline pipeline = new IngestPipeline();
        WeeklyIntelBrief brief;
        try {
            brief = pipeline.run();
        } catch (CalendarIngestException ex) {
            log.error("Calendar ingest failed — refusing to produce brief: {}", ex.getMessage());
            return 2;
        }

        LocalDate ingestDate = brief.generatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        Path out = WeeklyIntelBriefIO.briefPathForDate(WeeklyBuilderPaths.intelRoot(repoRoot), ingestDate);
        WeeklyIntelBriefIO.write(brief, out);

        log.info("Wrote {} (status={}, weekStart={}, events={})",
            out, brief.ingestStatus(), brief.weekStart(), brief.calendarEvents().size());

        if (brief.ingestStatus() == IngestStatus.PARTIAL) {
            log.warn("Brief is PARTIAL — OANDA or COT data may be missing");
        }
        return 0;
    }
}
