# fire-backend (Spring Boot 4.1.2 · Java 21 · Maven)

Fresh project. The frontend calls only `/api/**`; this app calls the Python fire-service and falls back to
`resources/data/forecasts.json` when it is down. The calendar is computed in Java from `resources/data/calendar.json`.

```bash
mvn spring-boot:run                      # FIRE_MODEL_API_URL=http://localhost:8000 by default
mvn test                                 # no Python needed
```
No Maven wrapper is included: use a local Maven 3.9+, or generate one with `mvn wrapper:wrapper`.

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
