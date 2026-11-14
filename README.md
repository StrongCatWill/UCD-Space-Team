# Harmonized Fire Activity Calendar — UCD Space Team (NASA Space Apps 2026)

North America (US, Canada, Mexico) burning-activity calendar from harmonized NASA FIRMS MODIS Aqua + VIIRS NOAA-20.

| Module | Owner | What |
| --- | --- | --- |
| `contract/` | D1 + D2 | Shared Java records for every `out/` file and API shape, plus `FireJson.mapper()` |
| `pipeline/` | D1 | Batch CLI: FIRMS CSVs → `out/` (no Spring) |
| `api/` | D2 | Spring Boot REST API reading `out/` |

## Requirements
- **JDK 21** (`java -version` must say 21). macOS: `brew install openjdk@21` then
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- No Maven install needed: use `./mvnw`.
- FIRMS key only via env var: `export FIRMS_KEY=...` (never commit it).

## Commands
```
./mvnw -q -DskipTests install                               # build all modules
./mvnw -pl contract,pipeline -am test                       # D1 tests
java -jar pipeline/target/pipeline.jar all                  # D1: rebuild out/ from cached inputs
./mvnw -pl api -am spring-boot:run -Dspring-boot.run.profiles=mock   # D2: mock API
./mvnw -pl api -am spring-boot:run -Dspring-boot.run.profiles=real   # D2: real data from out/
```
OpenAPI: http://localhost:8080/v3/api-docs (Swagger UI: `/swagger-ui.html`).

## Git-ignored data
`archive/` (FIRMS country CSVs, ~1.3 GB), `fixtures/` (cached API responses), `out/` (pipeline output).

Data credit: NASA FIRMS (https://firms.modaps.eosdis.nasa.gov).
