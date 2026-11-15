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
    void modelRejectionIsPassedThroughNotReplacedByFallback() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://model");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://model/forecast"))
              .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                      .withStatus(org.springframework.http.HttpStatus.NOT_FOUND).body("{\"detail\":\"no modelled cells in bbox\"}"));
        var props = new org.spaceapps.fire.config.FireProperties(
                new org.spaceapps.fire.config.FireProperties.ModelApi("http://model", "", "none"),
                new org.spaceapps.fire.config.FireProperties.Data("classpath:data/calendar.json", "classpath:data/forecasts.json"),
                new org.spaceapps.fire.config.FireProperties.Refresh(false, "0 0 0 * * *"), List.of(), "");
        var service = new org.spaceapps.fire.forecast.ForecastService(
                new org.spaceapps.fire.client.ModelApiClient(builder.build(), props), props,
                new org.springframework.core.io.DefaultResourceLoader(), mapper,
                new org.springframework.cache.concurrent.ConcurrentMapCacheManager());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.forecast(CALIFORNIA))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
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
