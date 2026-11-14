package ie.ucd.space.firecal.api.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ie.ucd.space.firecal.api.ApiTestBase;
import org.junit.jupiter.api.Test;

class MetaApiTest extends ApiTestBase {

    @Test
    void metaIsWrappedInEnvelopeWithSnakeCaseAndStringMonths() throws Exception {
        mvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.as_of").value("2026-11-14T08:00:00Z"))
                .andExpect(jsonPath("$.harmonization_version").value("mock-h1.0"))
                .andExpect(jsonPath("$.sources[0].name").value("NASA FIRMS MODIS C6.1"))
                .andExpect(jsonPath("$.data.switch_month").value("2025-01"))
                .andExpect(jsonPath("$.data.series_start").value("2003-01"))
                .andExpect(jsonPath("$.data.calibration.train.from").value(2018))
                .andExpect(jsonPath("$.data.calibration.holdout.to").value(2025))
                .andExpect(jsonPath("$.data.calibration.holdout_mape").value(18.4))
                .andExpect(jsonPath("$.data.calibration.overlap.from").value(2018))
                .andExpect(jsonPath("$.data.coverage.VIIRS_NOAA20.sp_until").value("2026-06-30"))
                .andExpect(jsonPath("$.data.demo_areas", hasSize(3)))
                .andExpect(jsonPath("$.data.demo_areas[0].bbox", hasSize(4)));
    }

    @Test
    void openApiDocsUseSnakeCaseAndYearMonthAsString() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/meta']").exists())
                .andExpect(jsonPath("$.components.schemas.MetaCalibration.properties.holdout_mape").exists())
                .andExpect(jsonPath("$.components.schemas.MetaData.properties.switch_month.type").value("string"));
    }

    @Test
    void adminReloadSwapsStore() throws Exception {
        mvc.perform(post("/admin/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reloaded").value(true))
                .andExpect(jsonPath("$.cell_months").value(2844));
    }

    @Test
    void corsAllowsFrontendDevOrigin() throws Exception {
        mvc.perform(options("/api/meta")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
