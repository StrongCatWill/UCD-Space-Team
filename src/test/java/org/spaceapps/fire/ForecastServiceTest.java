package org.spaceapps.fire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.spaceapps.fire.dto.Bbox;
import org.spaceapps.fire.dto.ForecastRequest;
import org.spaceapps.fire.dto.ForecastResponse;
import org.spaceapps.fire.forecast.FallbackForecastStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ForecastServiceTest {

    private static final ForecastRequest CALIFORNIA = new ForecastRequest(List.of(-124.5, 32.5, -114.0, 42.0), null, 3);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void fallbackFiltersBboxAndLimitsDays() {
        var store = FallbackForecastStore.load(new ClassPathResource("data/forecasts.json"), mapper);
        ForecastResponse r = store.forecast(CALIFORNIA);
        Bbox box = Bbox.of(CALIFORNIA.bbox());
        assertThat(r.source()).isEqualTo(FallbackForecastStore.SOURCE);
        assertThat(r.region()).hasSize(3);
        assertThat(r.items()).allMatch(c -> box.contains(c.lat(), c.lon()));
        assertThat(r.items()).hasSize(r.cells() * 3);
    }

    @Test
    void liveResponseDeserializes() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://model");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://model/forecast")).andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                  {"modelVersion":"fire-lstm-v1","asOf":"2026-11-07","cells":1,
                   "region":[{"date":"2026-11-08","expectedFireCells":0.3,"pMean":0.3,"pClimMean":0.1,"anomaly":3.0,"observedFireCells":null}],
                   "items":[{"date":"2026-11-08","cellId":"80_-240","lat":40.25,"lon":-119.75,"p":0.3,"pClim":0.1,"anomaly":3.0,"observed":null}]}
                  """, MediaType.APPLICATION_JSON));
        ForecastResponse r = builder.build().post().uri("/forecast").body(CALIFORNIA.withDefaults())
                .retrieve().body(ForecastResponse.class);
        assertThat(r.items().getFirst().p()).isEqualTo(0.3);
        assertThat(r.items().getFirst().observed()).isNull();
        server.verify();
    }

    @Test
    void serverErrorIsARestClientException() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://model");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://model/forecast")).andRespond(withServerError());
        try {
            builder.build().post().uri("/forecast").body(CALIFORNIA.withDefaults()).retrieve().body(ForecastResponse.class);
            assertThat(false).as("expected exception").isTrue();
        } catch (org.springframework.web.client.RestClientException expected) {
            // ForecastService catches exactly this type and switches to the fallback store
        }
    }
}
