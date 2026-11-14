# fire-backend (Spring Boot 4.1.2 · Java 21 · Maven)

Fresh project. The frontend calls only `/api/**`; this app calls the Python fire-service and falls back to
`resources/data/forecasts.json` when it is down. The calendar is computed in Java from `resources/data/calendar.json`.

```bash
INGEST_TOKEN=demo mvn spring-boot:run    # FIRE_MODEL_API_URL=http://localhost:8000 by default;
                                         # INGEST_TOKEN must equal the fire-service's, else /api/admin/refresh gets 401
mvn test                                 # no Python needed
```
No Maven wrapper is included: use a local Maven 3.9+, or generate one with `mvn wrapper:wrapper`.

## Run the whole stack (two terminals)

```bash
# 1) Python model service on :8000 (Python 3.12+; numpy 2.5.3 needs >=3.12)
cd fire-service
python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt   # first time only
./run_service.sh          # asks for the FIRMS MAP_KEY (hidden input; empty = no live ingest)

# 2) Spring Boot backend on :8080
INGEST_TOKEN=demo mvn spring-boot:run

curl localhost:8080/api/health                    # modelApi.status = "ok"
curl -X POST localhost:8080/api/admin/refresh     # pull latest FIRMS VIIRS data (needs MAP_KEY)
```
Get a free MAP_KEY at https://firms.modaps.eosdis.nasa.gov/api/. The key is never stored in the repo.
`fire-service/artifacts/` holds the trained model (fire-lstm-v1) and data up to 2026-11-07; `/api/admin/refresh`
extends it (written to `artifacts/state_live.npz`, git-ignored).

| Endpoint | |
|---|---|
| `POST /api/forecast` `{bbox:[w,s,e,n], asOf?, days?}` | live model, else fallback (`source` field says which) |
| `GET /api/calendar?bbox=w,s,e,n` | year×month matrix, monthly medians, latest complete month vs median, peak month, top years |
| `GET /api/calendar.csv?bbox=` | CSV download |
| `GET /api/accuracy?from&to&bbox` · `GET /api/observed?date&bbox` | proxied from Python |
| `GET /api/health` | backend + model API status |
| `POST /api/admin/refresh` | trigger satellite ingest; also daily if `FIRE_REFRESH_ENABLED=true` |

Replace `src/main/resources/data/*.json` with your own Colab artifacts (the bundled ones come from a 1-minute CPU smoke model).

## Boot 4 specifics used here
`spring-boot-starter-webmvc` / `-restclient` / `-webmvc-test` / `-restclient-test`; Jackson 3 (`tools.jackson.databind.json.JsonMapper`,
annotations stay `com.fasterxml.jackson.annotation`); timeouts via `spring.http.clients.*`.

## Build status — read this
Maven Central was unreachable where this was written, so **the Spring wiring has not been compiled or run**.
What was checked: all 21 files parse as Java; `dto/*`, `CalendarService`, `FallbackForecastStore` were compiled with
`javac 21` against stubs and passed 16 logic checks. First thing to do: `mvn test`. Most likely trouble spots:
starter artifact names, `MockRestServiceServer.bindTo(RestClient.Builder)`, `JsonMapper` bean injection.
