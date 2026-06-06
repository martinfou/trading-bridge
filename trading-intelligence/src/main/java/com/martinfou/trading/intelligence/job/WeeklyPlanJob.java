package com.martinfou.trading.intelligence.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.intelligence.brief.IngestStatus;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBriefIO;
import com.martinfou.trading.intelligence.ingest.CalendarIngestException;
import com.martinfou.trading.intelligence.ingest.IngestPipeline;
import com.martinfou.trading.intelligence.llm.LlmClient;
import com.martinfou.trading.intelligence.llm.LlmException;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import com.martinfou.trading.intelligence.plan.ReviewerStatus;
import com.martinfou.trading.intelligence.plan.WeeklyPlan;
import com.martinfou.trading.intelligence.plan.WeeklyPlanIO;
import com.martinfou.trading.intelligence.plan.WeeklyPlanManifest;
import com.martinfou.trading.intelligence.planner.PlannerValidationException;
import com.martinfou.trading.intelligence.planner.WeeklyPlanner;
import com.martinfou.trading.intelligence.template.TemplateRegistry;
import com.martinfou.trading.intelligence.time.WeekBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

/** Job 1: ingest → LLM planner/reviewer → pending/ (Epic 22.2). */
public final class WeeklyPlanJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklyPlanJob.class);

    private final Path repoRoot;
    private final IngestPipeline ingestPipeline;
    private final WeeklyPlanner planner;
    private final Clock clock;
    private final ObjectMapper mapper;

    public WeeklyPlanJob(Path repoRoot, IngestPipeline ingestPipeline, LlmClient llmClient) throws IOException {
        this(repoRoot, ingestPipeline, llmClient, Clock.systemUTC());
    }

    public WeeklyPlanJob(Path repoRoot, IngestPipeline ingestPipeline, LlmClient llmClient, Clock clock)
        throws IOException {
        this.repoRoot = repoRoot;
        this.ingestPipeline = ingestPipeline;
        this.planner = new WeeklyPlanner(llmClient, TemplateRegistry.loadDefault());
        this.clock = clock;
        this.mapper = WeeklyPlanIO.mapper();
    }

    public Result run() throws Exception {
        WeeklyBuilderPaths.ensureLayout(repoRoot);
        Path intelRoot = WeeklyBuilderPaths.intelRoot(repoRoot);

        WeeklyIntelBrief brief;
        try {
            brief = ingestPipeline.run();
        } catch (CalendarIngestException ex) {
            log.error("Calendar ingest failed — no plan produced: {}", ex.getMessage());
            writeFailure("calendar-ingest-failed", ex.getMessage());
            return Result.calendarFailed();
        }

        LocalDate ingestDate = brief.generatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        Path briefPath = WeeklyIntelBriefIO.briefPathForDate(intelRoot, ingestDate);
        WeeklyIntelBriefIO.write(brief, briefPath);
        String briefRef = briefPath.getFileName().toString();
        String briefSha256 = sha256(briefPath);

        if (brief.ingestStatus() == IngestStatus.FAILED) {
            writeFailure("ingest-failed", "Brief ingestStatus=FAILED");
            return Result.ingestFailed();
        }

        WeeklyPlan plan;
        try {
            plan = planner.plan(brief, briefRef);
        } catch (PlannerValidationException ex) {
            if (ex.schemaError()) {
                writeDlq(WeekBounds.weekId(brief.weekStart()), ex.getMessage(), briefRef);
                return Result.dlq(ex.getMessage());
            }
            writeFailure("planner-validation", ex.getMessage());
            return Result.failed(ex.getMessage());
        } catch (LlmException ex) {
            writeFailure("llm-error", ex.getMessage());
            return Result.failed(ex.getMessage());
        }

        if (plan.reviewerStatus() == ReviewerStatus.REJECTED) {
            writeFailure("reviewer-rejected", "Reviewer rejected plan for " + plan.weekId());
            return Result.rejected(plan.weekId());
        }

        Path pendingPlan = WeeklyPlanIO.planPath(WeeklyBuilderPaths.pending(repoRoot), plan.weekId());
        WeeklyPlanIO.write(plan, pendingPlan);

        WeeklyPlanManifest manifest = new WeeklyPlanManifest(
            plan.weekId(),
            briefRef,
            briefSha256,
            clock.instant(),
            pendingPlan.getFileName().toString()
        );
        Path manifestPath = WeeklyPlanIO.manifestPath(WeeklyBuilderPaths.pending(repoRoot), plan.weekId());
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);

        log.info("Approved plan written to {} (picks={})", pendingPlan, plan.picks().size());
        return Result.approved(plan.weekId(), pendingPlan);
    }

    private void writeFailure(String reason, String detail) throws IOException {
        Path failedDir = WeeklyBuilderPaths.failed(repoRoot);
        Files.createDirectories(failedDir);
        Path reasonFile = failedDir.resolve("reason-" + clock.instant().toString().replace(':', '-') + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(reasonFile.toFile(), Map.of(
            "reason", reason,
            "detail", detail,
            "at", clock.instant().toString()
        ));
        log.warn("Wrote failure reason to {}", reasonFile);
    }

    private void writeDlq(String weekId, String detail, String briefRef) throws IOException {
        Path dlqDir = WeeklyBuilderPaths.dlq(repoRoot);
        Files.createDirectories(dlqDir);
        Path reasonFile = dlqDir.resolve("weekly-plan-" + weekId + "-dlq.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(reasonFile.toFile(), Map.of(
            "weekId", weekId,
            "briefRef", briefRef,
            "detail", detail,
            "at", clock.instant().toString()
        ));
        log.warn("Malformed plan sent to dlq: {}", reasonFile);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IOException("SHA-256 failed", ex);
        }
    }

    public record Result(Status status, String weekId, Path planPath, String message) {
        public enum Status {
            APPROVED, REJECTED, CALENDAR_FAILED, INGEST_FAILED, DLQ, FAILED
        }

        static Result approved(String weekId, Path planPath) {
            return new Result(Status.APPROVED, weekId, planPath, "approved");
        }

        static Result rejected(String weekId) {
            return new Result(Status.REJECTED, weekId, null, "reviewer rejected");
        }

        static Result calendarFailed() {
            return new Result(Status.CALENDAR_FAILED, null, null, "calendar ingest failed");
        }

        static Result ingestFailed() {
            return new Result(Status.INGEST_FAILED, null, null, "ingest failed");
        }

        static Result dlq(String message) {
            return new Result(Status.DLQ, null, null, message);
        }

        static Result failed(String message) {
            return new Result(Status.FAILED, null, null, message);
        }
    }
}
