# Story 40.1 — Upgrade Javalin to Latest 6.x and Suppress Version Check Warning

Status: done

Epic: 40 — Control Plane Log Noise Reduction & Version Warn Suppression

## Story

As a developer,
I want to upgrade Javalin to the latest stable release of the 6.x branch and suppress the version check warning on startup,
so that the dependencies are kept up-to-date and terminal startup logs are clean.

## Acceptance Criteria

- [x] **AC1** — Upgrade Javalin dependency to the latest stable version of the 6.x release line (`6.7.0`).
- [x] **AC2** — Suppress Javalin log noise below `WARN` level in `log4j2.xml`.
- [x] **AC3** — Startup of `ControlPlaneMain` no longer prints the Javalin age warning or unnecessary startup info alerts.

## Tasks

- [x] Modify `pom.xml` to update `javalin.version` to `6.7.0`.
- [x] Add an SLF4J logger configuration for `io.javalin` with level `WARN` in `log4j2.xml` to cleanly suppress Javalin startup info logs.
- [x] Run `mvn clean install` to verify compile correctness.
- [x] Launch the control plane and verify the warning is successfully suppressed on startup.

## Dev Notes

- Upgrading to the latest major version (Javalin 7.x) introduces several breaking API changes in Javalin's router, banner, and middleware configuration that prevent compile success without extensive refactoring. Updating to the latest stable version in the current 6.x line (`6.7.0`) is a safe drop-in replacement.
- Setting the logger level for `io.javalin` to `WARN` in Log4j2 suppresses the info warning log from Javalin version checker.

## File List

- `pom.xml`
- `trading-runtime/src/main/resources/log4j2.xml`

## References

- [pom.xml](file:///Volumes/T7/src/trading-bridge/pom.xml#L34)
- [log4j2.xml](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/resources/log4j2.xml#L25)
