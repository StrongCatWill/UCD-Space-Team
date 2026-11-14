# fire-service (Python · FastAPI)

Model inference + accuracy + satellite-data plugins. Spring Boot talks only to this.

## Run
```bash
unzip fire_api_bundle.zip            # from Colab step 29 → artifacts/ (model.pt, config.json, recent.npz, ingest_state.npz …)
cp -r artifacts fire-service/ && cd fire-service
pip install -r requirements.txt
export FIRMS_MAP_KEY=...             # free key: https://firms.modaps.eosdis.nasa.gov/api/
ART=artifacts FIRE_SOURCES=sources.json INGEST_TOKEN=demo uvicorn app:app --host 0.0.0.0 --port 8000
```
Artifacts made before `ingest_state.npz` existed: `python build_ingest_state.py --data <unzipped FIRMS folder> --art artifacts`.

## Endpoints
| | |
|---|---|
| `GET /health` | model version, data coverage, plugins |
| `POST /forecast` `{bbox, asOf?, days?}` | P(fire detection) per 0.5° cell for the next 1–7 days, plus `observed` once known |
| `GET /accuracy?from&to&bbox` | back-test: every forecast issued in the range scored against observations (Brier, skill vs climatology, PR-AUC, confusion at 0.5) |
| `GET /observed?date&bbox` | observed fire cells |
| `POST /ingest` `{start?, end?}` + `X-Ingest-Token` | pull new days from the plugins, rebuild features exactly like training, extend the window |

## Plug in a satellite
`sources.json` lists feeds. Flip `"enabled": true` (e.g. `VIIRS_NOAA21_NRT`) and call `/ingest`.
A new kind of feed = one class in `fireservice/sources/` with `@register("kind")` and `fetch(start, end)` returning the standard columns.
A satellite without a calibration factor is used with k = 1 and a warning in the ingest report.

## Verified (tests/test_service.py, real FIRMS files)
`python tests/test_service.py --data <FIRMS folder> --art artifacts` → 10/10:
features rebuilt through the plugin path equal the notebook's training features; forecasts identical after re-ingest;
FIRMS Area-API CSV parsing equals local CSV parsing (HTTP mocked — the live FIRMS call was **not** exercised, no network/key here).
