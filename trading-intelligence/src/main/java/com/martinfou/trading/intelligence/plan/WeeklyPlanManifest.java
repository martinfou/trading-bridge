package com.martinfou.trading.intelligence.plan;

import java.time.Instant;

/** Sidecar metadata written alongside an approved pending plan (Epic 22.2). */
public record WeeklyPlanManifest(
    String weekId,
    String briefRef,
    String briefSha256,
    Instant reviewedAt,
    String planFile
) {}
