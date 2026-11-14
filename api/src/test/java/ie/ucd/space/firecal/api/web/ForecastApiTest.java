package ie.ucd.space.firecal.api.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ie.ucd.space.firecal.api.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** SPC is disabled in tests, so a US AOI reports upstream_unavailable while the experimental estimate is still served. */
class ForecastApiTest extends ApiTestBase {

    @Test
    void usAreaWithoutSpcCopyIsUpstreamUnavailableButHasExperimental() throws Exception {
        mvc.perform(post("/api/forecast").contentType(MediaType.APPLICATION_JSON).content("{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.official_status").value("upstream_unavailable"))
                .andExpect(jsonPath("$.data.official", hasSize(0)))
                .andExpect(jsonPath("$.data.experimental.target_month").value("2026-12"))
                .andExpect(jsonPath("$.data.experimental.expected_mec").isNumber())
                .andExpect(jsonPath("$.data.experimental.scale_s").isNumber())
                .andExpect(jsonPath("$.data.experimental.method").value("baseline_median_x_recent_ratio"));
    }

    @Test
    void mexicoHasNoFeed() throws Exception {
        mvc.perform(post("/api/forecast").contentType(MediaType.APPLICATION_JSON).content("{\"aoi\":{\"type\":\"demo\",\"area_id\":\"mx_sierra\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.official_status").value("no_feed"));
    }
}
