package ie.ucd.space.firecal.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ie.ucd.space.firecal.contract.api.*;
import ie.ucd.space.firecal.contract.files.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractJsonTest {
    private final ObjectMapper om = FireJson.mapper();

    private <T> JsonNode roundTrip(T value, Class<T> type) throws Exception {
        String json = om.writeValueAsString(value);
        T back = om.readValue(json, type);
        assertEquals(value, back, "round trip changed the value: " + json);
        return om.readTree(json);
    }

    private static void assertFields(JsonNode n, String... names) {
        List<String> actual = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(actual::add);
        assertEquals(Arrays.asList(names), actual);
    }

    @Test
    void cellMonth() throws Exception {
        var v = new CellMonth("387_-1222", 38.75, -122.15, Country.United_States, YearMonth.of(2020, 8),
                412.3, 415.0, 380, Series.VIIRS_NOAA20, SourceFlag.VIIRS_CAL, false);
        JsonNode n = roundTrip(v, CellMonth.class);
        assertFields(n, "cell_id", "lat", "lon", "parent", "ym", "mec", "mec_with_static", "raw_count",
                "raw_sensor", "source_flag", "provisional");
        assertEquals("2020-08", n.get("ym").asText());
        assertEquals("United_States", n.get("parent").asText());
    }

    @Test
    void rawMonth() throws Exception {
        var v = new RawMonth("387_-1222", 38.75, -122.15, Country.Canada, YearMonth.of(2003, 1),
                Series.MODIS_TERRA, 3, 41.2, true);
        assertFields(roundTrip(v, RawMonth.class), "cell_id", "lat", "lon", "parent", "ym", "series",
                "raw_count", "frp_sum", "provisional");
    }

    @Test
    void calibration() throws Exception {
        var f = new CalibrationFactor("B_35_-125", Country.United_States, 8, 0.412, 0.35, 0.47, 6,
                CalibrationLevel.region);
        var v = new Calibration("h1.0", Instant.parse("2026-11-14T08:00:00Z"), new YearRange(2018, 2023),
                YearMonth.of(2025, 1), YearMonth.of(2003, 1), List.of(f), List.of(), List.of(),
                new CalibrationValidation(new YearRange(2024, 2025), 18.2, 0.91,
                        Map.of("Mexico", new ValidationScore(25.0, 0.8))));
        JsonNode n = roundTrip(v, Calibration.class);
        assertFields(n, "harmonization_version", "generated_at", "train", "switch_month", "series_start",
                "regions", "country", "north_america", "validation");
        assertFields(n.get("regions").get(0), "region_id", "parent", "month", "a", "a_p25", "a_p75",
                "n_years", "level");
        assertFields(n.get("validation"), "holdout", "mape", "corr", "by_country");
        assertEquals("2026-11-14T08:00:00Z", n.get("generated_at").asText());
        assertEquals("2025-01", n.get("switch_month").asText());
        assertEquals(2018, n.get("train").get("from").asInt());
        assertEquals(25.0, n.get("validation").get("by_country").get("Mexico").get("mape").asDouble());
    }

    @Test
    void coverageKeysAreUpperCase() throws Exception {
        var v = new Coverage(new SeriesCoverage(LocalDate.of(2002, 7, 4), LocalDate.of(2026, 8, 31)),
                new SeriesCoverage(LocalDate.of(2018, 1, 1), LocalDate.of(2026, 6, 30)));
        JsonNode n = roundTrip(v, Coverage.class);
        assertFields(n, "MODIS_AQUA", "VIIRS_NOAA20");
        assertFields(n.get("MODIS_AQUA"), "from", "sp_until");
        assertEquals("2026-08-31", n.get("MODIS_AQUA").get("sp_until").asText());
    }

    @Test
    void demoAreaConfig() throws Exception {
        var v = new DemoAreaConfig("norcal", "Northern California", Country.United_States,
                List.of(-124.5, 38.0, -120.0, 42.0));
        assertFields(roundTrip(v, DemoAreaConfig.class), "area_id", "name", "country", "bbox");
    }

    @Test
    void aoiRequestPolymorphism() throws Exception {
        String json = """
            {"aoi": {"type": "circle", "lat": 38.5, "lon": -121.5, "radius_km": 50},
             "start": "2003-01", "end": "2026-11", "baseline": {"from": 2003, "to": 2025},
             "include_static": false, "min_conf": "nominal"}""";
        AoiRequest r = om.readValue(json, AoiRequest.class);
        assertEquals(new CircleAoi(38.5, -121.5, 50), r.aoi());
        assertEquals(YearMonth.of(2026, 11), r.end());
        assertEquals(ConfidenceClass.nominal, r.minConf());

        AoiRequest demo = om.readValue("{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"}}", AoiRequest.class);
        assertEquals(new DemoAoi("norcal"), demo.aoi());

        AoiRequest poly = om.readValue("""
            {"aoi":{"type":"polygon","geojson":{"type":"Polygon",
              "coordinates":[[[-122,38],[-121,38],[-121,39],[-122,38]]]}}}""", AoiRequest.class);
        PolygonAoi p = assertInstanceOf(PolygonAoi.class, poly.aoi());
        assertEquals(4, p.geojson().coordinates().get(0).size());

        JsonNode n = om.readTree(om.writeValueAsString(r));
        assertEquals("circle", n.get("aoi").get("type").asText());
        assertTrue(n.get("aoi").has("radius_km"));
    }

    @Test
    void calendarEnvelopeAndNoData() throws Exception {
        var ok = new CalendarCell(YearMonth.of(2020, 8), 412.3, 380, Series.MODIS_AQUA, 120.0, 150.5, 243.6,
                174.0, null, null, SourceFlag.MODIS, MonthStatus.ok);
        var none = new CalendarCell(YearMonth.of(2002, 12), null, null, null, null, null, null, null, null,
                null, null, MonthStatus.no_data);
        var data = new CalendarData(12345.6, YearMonth.of(2025, 1), List.of(ok, none));
        var env = new Envelope<>(Instant.parse("2026-11-14T08:00:00Z"), "h1.0",
                List.of(new DataSource("NASA FIRMS MODIS C6.1", DataLevel.SP)), data);
        JsonNode n = om.readTree(om.writeValueAsString(env));
        assertFields(n, "as_of", "harmonization_version", "sources", "data");
        assertFields(n.get("data"), "aoi_area_km2", "switch_month", "cells");
        assertFields(n.get("data").get("cells").get(0), "ym", "mec", "raw_count", "raw_sensor", "median", "mean",
                "anomaly_pct", "anomaly_pct_mean", "ci_low", "ci_high", "source_flag", "status");
        JsonNode nd = n.get("data").get("cells").get(1);
        assertTrue(nd.get("mec").isNull(), "no_data month must be null, never 0");
        assertEquals("no_data", nd.get("status").asText());
        assertEquals(data, om.readValue(om.writeValueAsString(data), CalendarData.class));
    }

    @Test
    void summaryForecastDeviceMeta() throws Exception {
        var summary = new SummaryData(
                new SummaryCards(9000.1, new ThisMonthCard(YearMonth.of(2026, 11), 10.0, 5.0, 100.0),
                        new PeakYear(2020, 3000.0), new PeakMonth(YearMonth.of(2020, 9), 1200.0)),
                List.of(new SeasonalityPoint(8, 120.0, 80.0, 200.0, 150.5, null)),
                List.of(new YearTotal(2026, 800.0, false)), 400.0,
                List.of(new AnomalyMonth(YearMonth.of(2020, 9), 1200.0, 300.0, 300.0)));
        JsonNode s = roundTrip(summary, SummaryData.class);
        assertFields(s, "cards", "seasonality", "yearly", "yearly_mean", "top_anomalies");
        assertFields(s.get("cards"), "total_mec", "this_month", "peak_year", "peak_month");
        assertFields(s.get("seasonality").get(0), "month", "median", "p25", "p75", "mean", "this_year");

        var forecast = new ForecastData(OfficialStatus.available,
                List.of(new OfficialOutlook("NOAA SPC", "Fire Weather Outlook Day 1", OutlookLevel.ELEVATED,
                        "Elevated", Instant.parse("2026-11-14T06:00:00Z"), Instant.parse("2026-11-15T12:00:00Z"),
                        "https://www.spc.noaa.gov/products/fire_wx/")),
                new ExperimentalEstimate(YearMonth.of(2026, 12), 40.0, 20.0, 60.0, 0.9, 1.2,
                        "baseline_median_x_recent_scale", "Experimental"));
        JsonNode f = roundTrip(forecast, ForecastData.class);
        assertFields(f.get("experimental"), "target_month", "expected_mec", "p25", "p75", "p_any", "scale_s",
                "method", "label");
        assertFields(f.get("official").get(0), "provider", "product", "level", "level_label", "issued_at",
                "valid_to", "link");

        var device = new DevicePayload("norcal", "Northern California", Instant.parse("2026-11-14T08:00:00Z"),
                YearMonth.of(2026, 10), 52.0, 20.0, 160.0, Arrays.asList(1.0, 2.0, null, 4.0, 5.0, 6.0, 7.0,
                8.0, 9.0, 10.0, 11.0, 52.0), new DeviceOutlook("above_normal", "experimental"),
                new DeviceOfficial(OutlookLevel.NONE, "NOAA SPC", null), true, false);
        assertFields(roundTrip(device, DevicePayload.class), "area_id", "area_name", "as_of", "month", "mec",
                "median", "anomaly_pct", "last12", "outlook", "official", "alert", "snapshot");

        var meta = new MetaData(YearMonth.of(2025, 1), YearMonth.of(2003, 1),
                new MetaCalibration(new YearRange(2018, 2025), new YearRange(2018, 2023),
                        new YearRange(2024, 2025), 18.2, 0.91), null, List.of());
        assertFields(roundTrip(meta, MetaData.class).get("calibration"), "overlap", "train", "holdout",
                "holdout_mape", "holdout_corr");

        var file = new DemoAreaFile("norcal", new CalendarData(1.0, YearMonth.of(2025, 1), List.of()), summary,
                device);
        assertFields(roundTrip(file, DemoAreaFile.class), "area_id", "calendar", "summary", "device");
    }

    @Test
    void apiError() throws Exception {
        assertFields(roundTrip(new ApiError(ErrorCode.AOI_OUTSIDE_NA, "AOI must be inside US/Canada/Mexico"),
                ApiError.class), "code", "message");
    }
}
