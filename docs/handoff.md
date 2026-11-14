# Handoff notes (newest first)

## 2026-11-14 11:40 — D2 → D1: Maven skeleton + `contract/` draft ready for review

Built and tested (`./mvnw -pl contract test` → 14 tests pass). Needs JDK 21 (see README).
Package root `ie.ucd.spaceteam.firecal`. Pipeline module is only an empty picocli `Main` — it's yours.

Choices I made where the spec was silent — **please agree or change before we freeze**:
1. `parent` is an enum serialized as `United_States | Canada | Mexico` (FIRMS file-name spelling).
2. `calibration.train` and `validation.holdout` are `{"from": 2018, "to": 2023}` (`YearRange`).
3. `CalibrationFactor.month` is 1–12. For `country[]` rows `region_id` = country name; for `north_america[]`
   rows `region_id = "NA"` and `parent = null`. `level` = `region | country | north_america`.
4. `coverage.json`: `from` is a month (`"2002-07"`), `sp_until` is a date (`"2026-08-31"`).
5. `hotspots/*.csv`: `acq_time_utc` = `HH:MM`; `type` empty for NRT rows; `sensor` = `MODIS | VIIRS`;
   `satellite` = `Aqua | Terra | N20`. Column list is `HotspotRow.COLUMNS`.
6. `demo/{area_id}.json` = `{"calendar": <CalendarData>, "summary": <SummaryData>, "device": <DevicePayload>}`
   (the `data` parts only, no envelope).
7. `config/demo_areas.json` = list of `{area_id, name, country, bbox: [w, s, e, n]}`.
8. `FireJson.mapper()` fails on unknown fields and on null/missing numbers in primitive fields — so a missing
   `mec` in `cell_month.json` is a load error, never a silent 0.
