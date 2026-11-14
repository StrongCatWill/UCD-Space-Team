# Handoff notes (newest first)

## 2026-11-14 ~12:25 — D1 → team: `out/model/` table for models/ (uses S-NPP, outside the calendar)

New D1 command `pipeline modeltable` writes `out/model/harmonised_cell_month.csv` (+ `.meta.json`): dense
0.5° cell × month, 2002-01..2025-12, 9,423 cells, 2.71M rows, schema = `models/README.md` "Input schema"
(validated with `fire_model.data.load_harmonised`).

- **Spec deviation, models only:** the table carries **VIIRS S-NPP** (`viirs_snpp_*`, 2012+) because the
  backcast model needs the one VIIRS series covering 2012–2026. NOAA-20 is also there (`viirs_noaa20_*`, 2018+).
  The calendar contract (`cell_month.json` etc.) is unchanged: Aqua + NOAA-20, no S-NPP. **D2: nothing to do**;
  `api/` should not read `out/model/`.
- Which series the calendar finally shows (our MEC vs the models' VIIRS-equivalent backcast) is still open
  (see models/README.md "Relationship to the team calendar").
- `land_cover` is a placeholder (`unknown`); `region` = 5° block id (same scheme as calibration.json).
- Archive fix: `modis_2020_Mexico.csv` was truncated by an earlier interrupted download; re-downloaded
  (`download` now writes `.part` + rename, so this can't recur).

## 2026-11-14 ~11:40 — D1 → D2: skeleton + contract draft ready for review (freeze 11:30, slightly late)

Built and green: `./mvnw -q -DskipTests install`, `./mvnw -pl contract,pipeline -am test` (9 contract round-trip tests).

**Please review `contract/` and reply here (OK / changes).** Things I decided that you may want to change:

1. **`api/` stub** — I created a minimal `api/pom.xml` + `ApiApplication` only so the reactor builds. It's yours; replace freely.
2. **Months are `java.time.YearMonth`** → JSON `"2026-11"` via `FireJson`. springdoc will probably render YearMonth as an
   object in `/v3/api-docs`; please add `SpringDocUtils.getConfig().replaceWithClass(YearMonth.class, String.class)`
   so openapi-typescript sees `string`.
3. **Use `FireJson.builder()` for Spring's mapper** (e.g. a `Jackson2ObjectMapperBuilderCustomizer` or expose
   `FireJson.mapper()` as the `@Primary ObjectMapper` bean) so API and files serialize identically.
   `FAIL_ON_UNKNOWN_PROPERTIES` is disabled.
4. **Enums with lowercase constants** where the spec shows lowercase values: `MonthStatus`, `OfficialStatus`,
   `ConfidenceClass` (`min_conf`), `CalibrationLevel`. `Country` constants = file tokens `United_States|Canada|Mexico`.
5. **`OutlookLevel {NONE, ELEVATED, CRITICAL, EXTREMELY_CRITICAL}`** — ordered, so the alert rule is
   `level.compareTo(ELEVATED) >= 0`. Map SPC labels onto it.
6. **Device `outlook{level, source}`** — spec is ambiguous; I made `level` a free `String` (e.g. `"above_normal"`,
   from our experimental estimate) and `official.level` an `OutlookLevel`. Change if you meant something else.
7. **`aoi`** is a sealed interface with `"type"` discriminator (`DemoAoi`, `PolygonAoi`, `CircleAoi`).
   Polygon = GeoJSON **Polygon only** (`GeoJsonPolygon{type, coordinates}`) — no MultiPolygon in the MVP.
   No Bean Validation annotations in `contract/` (Jackson only) — validate in `api/`.
8. **Nulls**: `CalendarCell` numeric fields are boxed and serialized as `null` for `no_data` (never 0).
9. **`calibration.json` levels**: `region_id` = `B_<lat5>_<lon5>` / country name / `"NA"`; `parent` null at NA level.
10. **`demo_areas.json`** is a JSON array of `DemoAreaConfig{area_id, name, country, bbox[w,s,e,n]}`, reused in
    `/api/meta`. Third area id = `mx_sierra` (Sierra Madre Occidental). Bboxes are provisional until D1 checks
    detection density.
11. CSV column lists live in `HotspotCsv.COLUMNS` and `MonthlyCsv.COLUMNS`.
