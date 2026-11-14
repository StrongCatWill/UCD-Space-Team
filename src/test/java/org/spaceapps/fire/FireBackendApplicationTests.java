package org.spaceapps.fire;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.spaceapps.fire.dto.ForecastRequest;
import org.spaceapps.fire.dto.ForecastResponse;
import org.spaceapps.fire.forecast.ForecastService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Full context with the model API pointed at a dead port: everything must still answer via the fallback. */
@SpringBootTest(properties = "fire.model-api.url=http://127.0.0.1:9")
class FireBackendApplicationTests {

    @Autowired
    ForecastService forecasts;

    @Test
    void contextLoadsAndFallsBackWhenModelIsDown() {
        ForecastResponse r = forecasts.forecast(new ForecastRequest(java.util.List.of(-124.5, 32.5, -114.0, 42.0), null, 2));
        assertThat(r.source()).isEqualTo("fallback-precomputed");
        assertThat(r.region()).hasSize(2);
    }
}
