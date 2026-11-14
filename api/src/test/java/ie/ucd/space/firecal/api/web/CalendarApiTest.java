package ie.ucd.space.firecal.api.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ie.ucd.space.firecal.api.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CalendarApiTest extends ApiTestBase {

    static final String NORCAL = "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"}}";

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void calendarForDemoAreaCoversSeriesStartToCurrentMonth() throws Exception {
        // 2003-01 .. 2026-11 = 23*12 + 11 = 287 months
        postJson("/api/calendar", NORCAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.harmonization_version").value("mock-h1.0"))
                .andExpect(jsonPath("$.data.switch_month").value("2025-01"))
                .andExpect(jsonPath("$.data.aoi_area_km2").value(170454.9))
                .andExpect(jsonPath("$.data.cells", hasSize(287)))
                .andExpect(jsonPath("$.data.cells[0].ym").value("2003-01"))
                .andExpect(jsonPath("$.data.cells[0].source_flag").value("MODIS"))
                .andExpect(jsonPath("$.data.cells[0].raw_sensor").value("MODIS_AQUA"))
                .andExpect(jsonPath("$.data.cells[286].ym").value("2026-11"))
                .andExpect(jsonPath("$.data.cells[286].status").value("in_progress"))
                .andExpect(jsonPath("$.data.cells[285].status").value("provisional"))
                .andExpect(jsonPath("$.data.cells[270].ym").value("2025-07"))
                .andExpect(jsonPath("$.data.cells[270].source_flag").value("VIIRS_CAL"))
                .andExpect(jsonPath("$.data.cells[270].status").value("ok"))
                .andExpect(jsonPath("$.data.cells[270].ci_low").isNumber());
    }

    @Test
    void noDataMonthsAreNullNotZero() throws Exception {
        postJson("/api/calendar", "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"},\"start\":\"2002-11\",\"end\":\"2003-01\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cells", hasSize(3)))
                .andExpect(jsonPath("$.data.cells[0].status").value("no_data"))
                .andExpect(jsonPath("$.data.cells[0].mec").value(nullValue()))
                .andExpect(jsonPath("$.data.cells[2].status").value("ok"));
    }

    @Test
    void circleAndPolygonAoisWork() throws Exception {
        postJson("/api/calendar", "{\"aoi\":{\"type\":\"circle\",\"lat\":26.15,\"lon\":-107.25,\"radius_km\":25},\"start\":\"2020-01\",\"end\":\"2020-12\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cells", hasSize(12)))
                .andExpect(jsonPath("$.data.aoi_area_km2").value(1963.5));
        postJson("/api/calendar", """
                {"aoi":{"type":"polygon","geojson":{"type":"Polygon","coordinates":[[[-108,25],[-106,25],[-106,27],[-108,27],[-108,25]]]}},
                 "start":"2020-01","end":"2020-03","baseline":{"from":2003,"to":2020},"include_static":true,"min_conf":"high"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cells", hasSize(3)));
    }

    @Test
    void validationErrors() throws Exception {
        postJson("/api/calendar", "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"},\"start\":\"2003-13\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_INPUT"))
                .andExpect(jsonPath("$.message").value(containsString("YYYY-MM")));
        postJson("/api/calendar", "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"},\"start\":\"2010-01\",\"end\":\"2009-01\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_INPUT"));
        postJson("/api/calendar", "{\"aoi\":{\"type\":\"hexagon\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_INPUT"));
        postJson("/api/calendar", "{\"aoi\":{\"type\":\"circle\",\"lat\":53.35,\"lon\":-6.26,\"radius_km\":10}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AOI_OUTSIDE_NA"));
        postJson("/api/calendar", "{\"aoi\":{\"type\":\"circle\",\"lat\":45,\"lon\":-100,\"radius_km\":600}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AOI_TOO_LARGE"));
        postJson("/api/calendar", "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_INPUT"));
    }

    @Test
    void summaryShape() throws Exception {
        postJson("/api/summary", NORCAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards.total_mec").isNumber())
                .andExpect(jsonPath("$.data.cards.this_month.ym").value("2026-11"))
                .andExpect(jsonPath("$.data.cards.peak_year.year").isNumber())
                .andExpect(jsonPath("$.data.cards.peak_month.ym").isString())
                .andExpect(jsonPath("$.data.seasonality", hasSize(12)))
                .andExpect(jsonPath("$.data.seasonality[7].month").value(8))
                .andExpect(jsonPath("$.data.yearly", hasSize(24)))
                .andExpect(jsonPath("$.data.yearly[0].complete").value(true))
                .andExpect(jsonPath("$.data.yearly[23].complete").value(false))
                .andExpect(jsonPath("$.data.yearly_mean").isNumber())
                .andExpect(jsonPath("$.data.top_anomalies", hasSize(10)));
    }

    @Test
    void monthlyCsvHasContractColumnsAndCredit() throws Exception {
        postJson("/api/monthly.csv", "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"},\"start\":\"2002-12\",\"end\":\"2003-02\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("monthly_2002-12_2003-02.csv")))
                .andExpect(header().string("X-Harmonization-Version", "mock-h1.0"))
                .andExpect(content().string(startsWith("# Data: NASA FIRMS")))
                .andExpect(content().string(containsString(
                        "year,month,mec,raw_count,raw_sensor,median_same_month,mean_same_month,anomaly_pct,ci_low,ci_high,source_flag,status,aoi_area_km2\n")))
                .andExpect(content().string(containsString("2002,12,,,,,,,,,,no_data,170454.9\n")))
                .andExpect(content().string(containsString("2003,1,")));
    }

    @Test
    void monthlyCsvErrorsAreJson() throws Exception {
        postJson("/api/monthly.csv", "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"nowhere\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_INPUT"));
    }

    @Test
    void openApiHasCalendarSchemas() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/calendar'].post").exists())
                .andExpect(jsonPath("$.paths['/api/summary'].post").exists())
                .andExpect(jsonPath("$.components.schemas.CalendarCell.properties.anomaly_pct").exists())
                .andExpect(jsonPath("$.components.schemas.CalendarCell.properties.ym.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.AoiRequest.properties.include_static").exists());
    }
}
