package ie.ucd.space.firecal.api.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ie.ucd.space.firecal.api.ApiTestBase;
import org.junit.jupiter.api.Test;

class DeviceApiTest extends ApiTestBase {

    @Test
    void devicePayloadHasNoEnvelope() throws Exception {
        mvc.perform(get("/api/device/norcal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.area_id").value("norcal"))
                .andExpect(jsonPath("$.area_name").value("Northern California"))
                .andExpect(jsonPath("$.as_of").value("2026-11-14T08:00:00Z"))
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.last12", hasSize(12)))
                .andExpect(jsonPath("$.outlook.source").value("experimental"))
                .andExpect(jsonPath("$.official.source").value("upstream_unavailable"))
                .andExpect(jsonPath("$.alert").isBoolean())
                .andExpect(jsonPath("$.snapshot").value(false));
    }

    @Test
    void snapshotModeServesFrozenPayload() throws Exception {
        mvc.perform(get("/api/device/norcal").param("snapshot", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot").value(true))
                .andExpect(jsonPath("$.month").value("2026-08"))
                .andExpect(jsonPath("$.alert").value(true));
    }

    @Test
    void unknownOrMalformedAreaId() throws Exception {
        mvc.perform(get("/api/device/atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BAD_INPUT"));
        mvc.perform(get("/api/device/NOR CAL!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_INPUT"));
    }

    @Test
    void mexicoDeviceHasNoFeedOfficial() throws Exception {
        mvc.perform(get("/api/device/mx_sierra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.official.source").value("no_feed"))
                .andExpect(jsonPath("$.official.level").doesNotExist());
    }
}
