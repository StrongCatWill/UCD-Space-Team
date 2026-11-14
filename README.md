# Harmonized Fire Activity Calendar

NASA Space Apps 2026. FIRMS MODIS + VIIRS hotspots harmonized into one MODIS-equivalent count (MEC) for
North America (US, Canada, Mexico), served as a burning-activity calendar.

Modules: `contract/` (shared records, Jackson only) · `pipeline/` (D1 batch CLI, no Spring) · `api/` (D2 Spring Boot).
Hand-off directory: `out/` (pipeline writes, api reads). Notes between lanes: [docs/handoff.md](docs/handoff.md).

## Requirements
JDK 21 (`JAVA_HOME` set). Maven comes from the wrapper (`./mvnw`, Maven 3.9.16).
The FIRMS key is read only from the env var `FIRMS_KEY`.

## Commands
```sh
./mvnw -q -DskipTests install                                          # build all modules
./mvnw -pl contract,pipeline -am test                                  # D1 tests
java -jar pipeline/target/pipeline.jar all                             # D1: rebuild out/ from cached inputs
java -jar pipeline/target/pipeline.jar download                        # D1: FIRMS country CSVs -> archive/ (skips existing)
java -jar pipeline/target/pipeline.jar download --only snpp --years 2012-2025   # S-NPP, models/ only
java -jar pipeline/target/pipeline.jar modeltable                      # D1: out/model/harmonised_cell_month.csv for models/
./mvnw -pl api -am spring-boot:run -Dspring-boot.run.profiles=mock     # D2: mock API
./mvnw -pl api -am spring-boot:run -Dspring-boot.run.profiles=real     # D2: real data from out/
```

## API (D2, `api/`)
Swagger UI: http://localhost:8080/swagger-ui.html · OpenAPI JSON: http://localhost:8080/v3/api-docs
(frontend: `npx openapi-typescript http://localhost:8080/v3/api-docs -o src/api/schema.d.ts`).

| Profile | Data | Notes |
| --- | --- | --- |
| `mock` (default) | `api/src/main/resources/mock/` (synthetic, `harmonization_version = mock-h1.0`) | regenerate: `python3 api/tools/gen_mock.py` |
| `real` | `out/` + `config/demo_areas.json` | refuses to start if a required file is missing |

`POST /admin/reload` re-reads the data dir atomically (old data kept on failure; set `FIRE_ADMIN_TOKEN` to require
header `X-Admin-Token`). Env: `FIRMS_KEY` (WMS proxy only). SPC outlooks refresh every 30 min; last good copy is kept.
Tests: `./mvnw -pl api -am test`.

## Pinned versions (Maven Central, 2026-11-14)
Spring Boot 3.5.16 (BOM imported, not parent) · springdoc-openapi 2.10.0 · picocli 4.7.7 · commons-csv 1.14.1 ·
JTS 1.20.0 · maven-shade-plugin 3.6.2.

Data: NASA FIRMS (MODIS C6.1, VIIRS NOAA-20 C2). Credit "NASA FIRMS" on every export.
