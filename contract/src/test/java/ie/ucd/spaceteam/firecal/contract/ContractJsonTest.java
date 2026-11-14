package ie.ucd.spaceteam.firecal.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import ie.ucd.spaceteam.firecal.contract.api.AoiRequest;
import ie.ucd.spaceteam.firecal.contract.api.CalendarData;
import ie.ucd.spaceteam.firecal.contract.api.CellStatus;
import ie.ucd.spaceteam.firecal.contract.api.CircleAoi;
import ie.ucd.spaceteam.firecal.contract.api.DemoAoi;
import ie.ucd.spaceteam.firecal.contract.api.DevicePayload;
import ie.ucd.spaceteam.firecal.contract.api.Envelope;
import ie.ucd.spaceteam.firecal.contract.api.ForecastData;
import ie.ucd.spaceteam.firecal.contract.api.MetaData;
import ie.ucd.spaceteam.firecal.contract.api.PolygonAoi;
import ie.ucd.spaceteam.firecal.contract.api.SummaryData;
import ie.ucd.spaceteam.firecal.contract.data.Calibration;
import ie.ucd.spaceteam.firecal.contract.data.CellMonth;
import ie.ucd.spaceteam.firecal.contract.data.Coverage;
import ie.ucd.spaceteam.firecal.contract.data.DemoArea;
import ie.ucd.spaceteam.firecal.contract.data.RawMonth;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every JSON sample below is written in the exact wire format of the team spec. Each test parses it into the
 * record and writes it back: the result must be identical, which pins every field name and value format.
 */
class ContractJsonTest {

    private final ObjectMapper m = FireJson.mapper();

    private <T> T roundTrip(String json, JavaType type) throws Exception {
        T value = m.readValue(json, type);
        assertEquals(m.readTree(json), m.readTree(m.writeValueAsString(value)), "round trip changed the JSON");
        return value;
    }

    private <T> T roundTrip(String json, Class<T> type) throws Exception {
        return roundTrip(json, m.constructType(type));
    }

    @Test
    void cellMonth() throws Exception {
        CellMonth c = roundTrip("""
                {"cell_id": "391_-1215", "lat": 39.15, "lon": -121.45, "parent": "United_States",
                 "ym": "2020-08", "mec": 42.0, "mec_with_static": 43.0, "raw_count": 42,
                 "raw_sensor": "MODIS_AQUA", "source_flag": "MODIS", "provisional": false}
                """, CellMonth.class);
        assertEquals(YearMonth.of(2020, 8), c.ym());
        assertEquals(Country.UNITED_STATES, c.parent());
    }

    @Test
    void rawMonth() throws Exception {
        roundTrip("""
                {"cell_id": "391_-1215", "lat": 39.15, "lon": -121.45, "parent": "Canada", "ym": "2026-10",
                 "series": "VIIRS_NOAA20", "raw_count": 7, "frp_sum": 31.4, "provisional": true}
                """, RawMonth.class);
    }

    @Test
    void calibration() throws Exception {
        Calibration c = roundTrip("""
                {"harmonization_version": "h1.0", "generated_at": "2026-11-14T13:00:00Z",
                 "train": {"from": 2018, "to": 2023}, "switch_month": "2025-01", "series_start": "2003-01",
                 "regions": [{"region_id": "B_35_-125", "parent": "United_States", "month": 8, "a": 0.412,
                              "a_p25": 0.371, "a_p75": 0.455, "n_years": 6, "level": "region"}],
                 "country": [{"region_id": "Mexico", "parent": "Mexico", "month": 4, "a": 0.38,
                              "a_p25": 0.3, "a_p75": 0.41, "n_years": 6, "level": "country"}],
                 "north_america": [{"region_id": "NA", "parent": null, "month": 1, "a": 0.4,
                                    "a_p25": 0.35, "a_p75": 0.44, "n_years": 6, "level": "north_america"}],
                 "validation": {"holdout": {"from": 2024, "to": 2025}, "mape": 18.3, "corr": 0.94,
                                "by_country": {"United_States": {"mape": 15.1, "corr": 0.95},
                                               "Canada": {"mape": 22.0, "corr": 0.91},
                                               "Mexico": {"mape": 19.4, "corr": 0.93}}}}
                """, Calibration.class);
        assertEquals(0.371, c.regions().get(0).aP25());
        assertNull(c.northAmerica().get(0).parent());
    }

    @Test
    void coverageUsesUpperCaseSeriesKeys() throws Exception {
        roundTrip("""
                {"MODIS_AQUA": {"from": "2002-07", "sp_until": "2026-08-31"},
                 "VIIRS_NOAA20": {"from": "2018-01", "sp_until": "2026-06-30"}}
                """, Coverage.class);
    }

    @Test
    void demoArea() throws Exception {
        roundTrip("""
                {"area_id": "norcal", "name": "Northern California", "country": "United_States",
                 "bbox": [-124.5, 38.0, -119.5, 42.0]}
                """, DemoArea.class);
    }

    @Test
    void aoiRequestAllThreeShapes() throws Exception {
        AoiRequest demo = roundTrip("""
                {"aoi": {"type": "demo", "area_id": "norcal"}, "start": "2003-01", "end": "2026-11",
                 "baseline": {"from": 2003, "to": 2025}, "include_static": false, "min_conf": "nominal"}
                """, AoiRequest.class);
        assertEquals("norcal", assertInstanceOf(DemoAoi.class, demo.aoi()).areaId());

        AoiRequest poly = roundTrip("""
                {"aoi": {"type": "polygon", "geojson": {"type": "Polygon",
                   "coordinates": [[[-122.0, 39.0], [-121.0, 39.0], [-121.0, 40.0], [-122.0, 39.0]]]}},
                 "start": "2020-01", "end": "2020-12", "baseline": null, "include_static": null, "min_conf": null}
                """, AoiRequest.class);
        assertInstanceOf(PolygonAoi.class, poly.aoi());

        AoiRequest circle = roundTrip("""
                {"aoi": {"type": "circle", "lat": 50.7, "lon": -120.3, "radius_km": 50.0},
                 "start": "2021-06", "end": "2021-09", "baseline": null, "include_static": true, "min_conf": "high"}
                """, AoiRequest.class);
        assertEquals(50.0, assertInstanceOf(CircleAoi.class, circle.aoi()).radiusKm());
    }

    @Test
    void calendarEnvelopeWithNoDataMonth() throws Exception {
        Envelope<CalendarData> env = roundTrip("""
                {"as_of": "2026-11-14T08:00:00Z", "harmonization_version": "h1.0",
                 "sources": [{"name": "NASA FIRMS MODIS C6.1", "level": "SP"}],
                 "data": {"aoi_area_km2": 81234.5, "switch_month": "2025-01", "cells": [
                   {"ym": "2025-08", "mec": 120.5, "raw_count": 290, "raw_sensor": "VIIRS_NOAA20", "median": 60.0,
                    "mean": 70.2, "anomaly_pct": 100.8, "anomaly_pct_mean": 71.7, "ci_low": 108.4, "ci_high": 133.1,
                    "source_flag": "VIIRS_CAL", "status": "ok"},
                   {"ym": "2026-12", "mec": null, "raw_count": null, "raw_sensor": null, "median": null,
                    "mean": null, "anomaly_pct": null, "anomaly_pct_mean": null, "ci_low": null, "ci_high": null,
                    "source_flag": null, "status": "no_data"}]}}
                """, m.getTypeFactory().constructType(new TypeReference<Envelope<CalendarData>>() {}));
        assertEquals(CellStatus.NO_DATA, env.data().cells().get(1).status());
        assertNull(env.data().cells().get(1).mec());
    }

    @Test
    void summary() throws Exception {
        roundTrip("""
                {"cards": {"total_mec": 15234.0,
                           "this_month": {"ym": "2026-11", "mec": 12.0, "median": 8.0, "anomaly_pct": 50.0},
                           "peak_year": {"year": 2021, "mec": 2100.3},
                           "peak_month": {"ym": "2021-08", "mec": 900.1}},
                 "seasonality": [{"month": 8, "median": 300.0, "p25": 150.0, "p75": 480.0, "mean": 340.2,
                                  "this_year": null}],
                 "yearly": [{"year": 2025, "mec": 1500.0, "complete": true}],
                 "yearly_mean": 662.3,
                 "top_anomalies": [{"ym": "2021-08", "mec": 900.1, "median": 300.0, "anomaly_pct": 200.0}]}
                """, SummaryData.class);
    }

    @Test
    void forecast() throws Exception {
        roundTrip("""
                {"official_status": "available",
                 "official": [{"provider": "NOAA SPC", "product": "Fire Weather Outlook Day 1", "level": "ELEVATED",
                               "level_label": "Elevated", "issued_at": "2026-11-14T06:00:00Z",
                               "valid_to": "2026-11-15T12:00:00Z", "link": "https://www.spc.noaa.gov/products/fire_wx/"}],
                 "experimental": {"target_month": "2026-12", "expected_mec": 5.2, "p25": 2.0, "p75": 8.0,
                                  "p_any": 0.9, "scale_s": 1.3, "method": "baseline_median_x_recent_scale",
                                  "label": "Experimental estimate"}}
                """, ForecastData.class);
    }

    @Test
    void device() throws Exception {
        roundTrip("""
                {"area_id": "norcal", "area_name": "Northern California", "as_of": "2026-11-14T08:00:00Z",
                 "month": "2026-11", "mec": 12.0, "median": 5.0, "anomaly_pct": 140.0,
                 "last12": [1.0, 2.0, 3.0, 10.0, 40.0, 80.0, 120.0, 60.0, 20.0, 5.0, 2.0, null],
                 "outlook": {"level": "ABOVE", "source": "experimental"},
                 "official": {"level": "ELEVATED", "source": "NOAA SPC", "issued": "2026-11-14T06:00:00Z"},
                 "alert": true, "snapshot": false}
                """, DevicePayload.class);
    }

    @Test
    void meta() throws Exception {
        roundTrip("""
                {"switch_month": "2025-01", "series_start": "2003-01",
                 "calibration": {"overlap": {"from": 2018, "to": 2025}, "train": {"from": 2018, "to": 2023},
                                 "holdout": {"from": 2024, "to": 2025}, "holdout_mape": 18.3, "holdout_corr": 0.94},
                 "coverage": {"MODIS_AQUA": {"from": "2002-07", "sp_until": "2026-08-31"},
                              "VIIRS_NOAA20": {"from": "2018-01", "sp_until": "2026-06-30"}},
                 "demo_areas": [{"area_id": "bc_interior", "name": "Interior British Columbia", "country": "Canada",
                                 "bbox": [-124.0, 49.0, -116.0, 54.0]}]}
                """, MetaData.class);
    }

    @Test
    void missingNumberIsAnErrorNotZero() {
        assertThrows(MismatchedInputException.class, () -> m.readValue("""
                {"cell_id": "1_1", "lat": 0.15, "lon": 0.15, "parent": "Mexico", "ym": "2020-01", "mec": null,
                 "mec_with_static": 1.0, "raw_count": 1, "raw_sensor": "MODIS_AQUA", "source_flag": "MODIS",
                 "provisional": false}
                """, CellMonth.class));
    }

    @Test
    void unknownFieldIsRejected() {
        assertThrows(MismatchedInputException.class,
                () -> m.readValue("{\"from\": 2018, \"to\": 2023, \"extra\": 1}",
                        ie.ucd.spaceteam.firecal.contract.data.YearRange.class));
    }

    @Test
    void hotspotColumnsMatchSpec() {
        assertEquals(List.of("hotspot_id", "sensor", "satellite", "latitude", "longitude", "acq_date",
                        "acq_time_utc", "daynight", "confidence_class", "frp_mw", "type", "data_source", "parent"),
                ie.ucd.spaceteam.firecal.contract.data.HotspotRow.COLUMNS);
    }
}
