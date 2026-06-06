package com.martinfou.trading.intelligence.deploy;

import com.martinfou.trading.intelligence.compile.CompileManifest;
import com.martinfou.trading.intelligence.plan.WeeklyPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/** Writes human-readable weekly plan summaries to deploy/weekly-plans/ (Epic 22.4). */
public final class WeeklyPlanMarkdownWriter {

    private WeeklyPlanMarkdownWriter() {}

    public static Path write(Path deployRoot, WeeklyPlan plan, CompileManifest manifest) throws IOException {
        LocalDate date = manifest.compiledAt().atZone(ZoneOffset.UTC).toLocalDate();
        Path dir = deployRoot.resolve("weekly-plans");
        Files.createDirectories(dir);
        Path target = dir.resolve(date.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md");

        StringBuilder md = new StringBuilder();
        md.append("# Weekly Plan ").append(plan.weekId()).append("\n\n");
        md.append("- **Brief ref:** ").append(plan.briefRef()).append("\n");
        md.append("- **Reviewer:** ").append(plan.reviewerStatus()).append("\n");
        md.append("- **Compiled at:** ").append(manifest.compiledAt()).append("\n");
        md.append("- **Origin:** ").append(manifest.origin()).append("\n\n");

        if (plan.picks().isEmpty()) {
            md.append("_No picks — NoTradeWeek._\n");
        } else {
            md.append("## Picks\n\n");
            for (WeeklyPlan.Pick pick : plan.picks()) {
                md.append("### ").append(pick.templateId()).append(" — ").append(pick.pair()).append("\n");
                md.append("- Direction: ").append(pick.direction()).append("\n");
                md.append("- Rationale: ").append(pick.rationale()).append("\n");
                md.append("- Sources: ").append(String.join(", ", pick.sources())).append("\n");
                if (pick.params() != null && !pick.params().isEmpty()) {
                    md.append("- Params: ").append(pick.params()).append("\n");
                }
                md.append("\n");
            }
        }

        if (!manifest.strategies().isEmpty()) {
            md.append("## Compiled strategies\n\n");
            md.append(manifest.strategies().stream()
                .map(s -> "- `" + s.strategyId() + "` → `" + s.className() + "`")
                .collect(Collectors.joining("\n")));
            md.append("\n");
        }

        Files.writeString(target, md.toString(), StandardCharsets.UTF_8);
        return target;
    }
}
