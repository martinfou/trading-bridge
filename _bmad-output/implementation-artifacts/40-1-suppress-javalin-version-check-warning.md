# Story 40.1 — Suppress Javalin Version Check Warning

Status: backlog

Epic: 40 — Control Plane Log Noise Reduction & Version Warn Suppression

## Story

As a developer,
I want the Javalin version check warning on startup to be suppressed,
so that the terminal startup logs are clean and free of unnecessary age/warning alerts.

## Acceptance Criteria

- [ ] **AC1** — Javalin configuration in `ControlPlaneServer.java` sets `config.core.disableVersionCheck = true` to disable the version check warning.
- [ ] **AC2** — Startup of `ControlPlaneMain` no longer prints the "Consider checking for a newer version" warning.

## Tasks

- [ ] Modify `ControlPlaneServer.java` inside `trading-runtime` to configure the Javalin instance with `config.core.disableVersionCheck = true`.
- [ ] Run `mvn clean install` to verify compile correctness.

## Dev Notes

- The version check warning is printed by Javalin 6.x's built-in version checking mechanism on startup if it detects the version is old.
- According to Javalin documentation and PR #2499, this check can be completely disabled by setting:
  ```java
  Javalin.create(config -> {
      config.core.disableVersionCheck = true;
      // ... rest of config ...
  })
  ```

## File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`

## References

- [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java#L299-L303)
