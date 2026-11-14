package ie.ucd.spaceteam.firecal.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class MockApiTest {

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder json(String url, String body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void calendarFillsMonthsOutsideCoverageWithNoData() throws Exception {
        mvc.perform(json("/api/calendar",
                        "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"},\"start\":\"2002-11\",\"end\":\"2003-02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.harmonization_version").value("h1.0-mock"))
                .andExpect(jsonPath("$.data.cells", hasSize(4)))
                .andExpect(jsonPath("$.data.cells[0].ym").value("2002-11"))
                .andExpect(jsonPath("$.data.cells[0].status").value("no_data"))
                .andExpect(jsonPath("$.data.cells[0].mec").value(nullValue()))
                .andExpect(jsonPath("$.data.cells[2].ym").value("2003-01"))
                .andExpect(jsonPath("$.data.cells[2].source_flag").value("MODIS"))
                .andExpect(jsonPath("$.data.cells[2].status").value("ok"));
    }

    @Test
    void errorsUseContractCodes() throws Exception {
        mvc.perform(json("/api/calendar",
                        "{\"aoi\":{\"type\":\"circle\",\"lat\":48,\"lon\":2,\"radius_km\":10},\"start\":\"2026-01\",\"end\":\"2026-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AOI_OUTSIDE_NA"));
        mvc.perform(json("/api/calendar",
                        "{\"aoi\":{\"type\":\"circle\",\"lat\":50,\"lon\":-100,\"radius_km\":900},\"start\":\"2026-01\",\"end\":\"2026-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AOI_TOO_LARGE"));
        mvc.perform(json("/api/summary",
                        "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"},\"start\":\"2026-13\",\"end\":\"2026-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_INPUT"));
    }

    @Test
    void mexicoHasNoOfficialFeed() throws Exception {
        mvc.perform(json("/api/forecast",
                        "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"mx_south\"},\"start\":\"2026-01\",\"end\":\"2026-11\"}"))
                .andExpect(jsonPath("$.data.official_status").value("no_feed"))
                .andExpect(jsonPath("$.data.experimental.target_month").value("2026-12"));
    }

    @Test
    void deviceHasNoEnvelopeAndSnapshotAlerts() throws Exception {
        mvc.perform(get("/api/device/norcal"))
                .andExpect(jsonPath("$.area_id").value("norcal"))
                .andExpect(jsonPath("$.last12", hasSize(12)))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/device/norcal").param("snapshot", "true"))
                .andExpect(jsonPath("$.snapshot").value(true))
                .andExpect(jsonPath("$.alert").value(true));
    }

    @Test
    void hotspotCsvHasCreditHeaderAndVersionColumn() throws Exception {
        mvc.perform(json("/api/hotspots.csv",
                        "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"},\"start\":\"2021-08\",\"end\":\"2021-08\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(startsWith("# Source: NASA FIRMS")));
    }
}
