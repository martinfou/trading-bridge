# third-party

Repo-local Maven repository (`third-party/m2`) for dependencies that are **not published to Maven Central**.
The root `pom.xml` declares it as the `local-third-party` repository, so `./mvnw`, CI and the Docker build
resolve these artifacts without any manual `install:install-file` step.

| Artifact | Version | Source | Notes |
|---|---|---|---|
| `com.interactivebrokers:tws-api` | 1045.01 | Official IB TWS API Java client (`com.ib.client`, `com.ib.controller`, `com.ib.contracts`) | Unmodified. Built from the sources previously vendored in `trading-data/src/main/java/com/ib`. A `-sources.jar` is included for IDE navigation. Depends on `protobuf-java:4.29.5`. |

## Upgrading the TWS API

1. Download the new API from https://interactivebrokers.github.io/ and build `source/JavaClient` into a jar.
2. Publish it here:
   ```bash
   ./mvnw deploy:deploy-file -Durl=file://$PWD/third-party/m2 -DrepositoryId=local-third-party \
     -Dfile=tws-api-<ver>.jar -Dsources=tws-api-<ver>-sources.jar \
     -DgroupId=com.interactivebrokers -DartifactId=tws-api -Dversion=<ver> -Dpackaging=jar
   ```
3. Bump `tws-api.version` in the root `pom.xml`, run `./mvnw verify`, delete the old version folder.

Never copy third-party source into a module's `src/` again.
