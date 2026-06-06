# Architecture — Bundle Java Control Plane in Desktop App

> Track : Quick Flow
> Date : 2026-06-04

## How it works

```
┌─────────────────────────────────────────────────┐
│  trading-bridge-desktop.AppImage                │
│  ├── resources/jre/bin/java      (jlink JRE)    │
│  ├── resources/jar/control-plane.jar (fat JAR)  │
│  └── app.asar                                    │
│       └── electron/main.ts                       │
│            ├── spawn(resources/jre/bin/java      │
│            │     -jar resources/jar/...jar)      │
│            ├── poll http://localhost:8080/ ↑     │
│            │     until 200                  │    │
│            ├── createWindow() ──────────────────┘ │
│            └── app.on('quit') kill JVM process    │
└─────────────────────────────────────────────────┘
```

## Components

| Layer | What | Why |
|-------|------|-----|
| **Fat JAR** | `trading-runtime` + all deps in one JAR via `maven-shade-plugin` | Portable, no classpath |
| **jlink JRE** | Minimal `jre/` directory (only required JDK modules) | ~45 MB vs 300 MB full JDK |
| **Main process** | `electron/main.ts` spawns JVM, waits for ready, graceful shutdown | User never manages Java |
| **extraResources** | electron-builder copies JRE + JAR into packaged app | Bundled, no downloads |

## Dev mode (npm run dev)

- Detects `process.env.VITE_DEV_SERVER_URL` → dev mode
- Finds `java` on system PATH (or `JAVA_HOME`)
- Finds fat JAR at `../../trading-runtime/target/trading-runtime-1.0.0-SNAPSHOT-shaded.jar`
- Spawns with `-Duser.dir=<project-root>` so data dirs resolve correctly

## Packaged mode (production build)

- JRE at `process.resourcesPath + /jre/bin/java`
- JAR at `process.resourcesPath + /jar/control-plane.jar`
- Spawns with `-Duser.dir=<app.getPath('userData')>` so data persiste dans `~/.config/trading-bridge/`

## CI flow

```
java job:
  mvn clean install -q
  mvn package -pl trading-runtime -am -DskipTests   # produce fat JAR
  upload: trading-runtime/target/*-shaded.jar

desktop [matrix: ubuntu, macos, windows]:
  needs: [java]
  download fat JAR → desktop-resources/jar/
  run jlink → desktop-resources/jre/
  npm ci + npm run build
  electron-builder (extraResources: desktop-resources/)
  upload artifact
```

## Data directory

Le control plane stocke la DB SQLite selon `RuntimeDataPaths` :
- Production : `~/.config/trading-bridge/events.db` (car userData)
- Dev : `data/runtime/events.db` (car cwd = repo root)

## Error handling

- JVM crash (exit code ≠ 0) → dialog + restart button
- Timeout waiting for control plane (15s) → dialog "Java backend failed to start"
- Port 8080 already in use → dialog "Another instance already running?"
