package org.spaceapps.fire.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under "fire:" in application.yml. */
@ConfigurationProperties(prefix = "fire")
public record FireProperties(ModelApi modelApi, Data data, Refresh refresh, List<String> corsOrigins,
                             String adminToken) {

    /** The Python fire-service (FastAPI). */
    /** auth = "gcp-id-token" when the fire-service is a private Cloud Run service; anything else = none. */
    public record ModelApi(String url, String ingestToken, String auth) {}

    /** Spring resource locations, e.g. classpath:data/calendar.json or file:/srv/fire/calendar.json */
    public record Data(String calendar, String forecasts) {}

    public record Refresh(boolean enabled, String cron) {}
}
