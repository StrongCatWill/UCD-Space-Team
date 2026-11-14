package org.spaceapps.fire.forecast;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spaceapps.fire.client.ModelApiClient;
import org.spaceapps.fire.config.FireProperties;
import org.spaceapps.fire.dto.ForecastRequest;
import org.spaceapps.fire.dto.ForecastResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ForecastService {

    public static final String LIVE = "model-api";
    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    private final ModelApiClient client;
    private final FallbackForecastStore fallback;
    private final CacheManager cacheManager;
    private final FireProperties props;

    @Autowired
    public ForecastService(ModelApiClient client, FireProperties props, ResourceLoader loader,
                           JsonMapper jsonMapper, CacheManager cacheManager) {
        this(client, FallbackForecastStore.load(loader.getResource(props.data().forecasts()), jsonMapper),
                cacheManager, props);
    }

    ForecastService(ModelApiClient client, FallbackForecastStore fallback, CacheManager cacheManager,
                    FireProperties props) {
        this.client = client;
        this.fallback = fallback;
        this.cacheManager = cacheManager;
        this.props = props;
    }

    /** Live model first; precomputed file if the Python service is down. Only live answers are cached. */
    @Cacheable(cacheNames = "forecast", unless = "#result.source() != 'model-api'")
    public ForecastResponse forecast(ForecastRequest request) {
        ForecastRequest req = request.withDefaults();
        try {
            ForecastResponse live = client.forecast(req);
            return live.withSource(LIVE);
        } catch (RestClientException e) {
            log.warn("model API unavailable ({}), serving precomputed forecasts as of {}", e.getMessage(), fallback.asOf());
            return fallback.forecast(req);
        }
    }

    /** Pull new satellite days through the Python plugins, then drop cached forecasts. */
    public Map<String, Object> refresh(String start, String end) {
        Map<String, Object> report = client.ingest(start, end);
        Cache cache = cacheManager.getCache("forecast");
        if (cache != null) cache.clear();
        return report;
    }

    @Scheduled(cron = "${fire.refresh.cron:0 30 6 * * *}")
    void scheduledRefresh() {
        if (!props.refresh().enabled()) return;
        try {
            log.info("scheduled refresh: {}", refresh(null, null));
        } catch (RestClientException e) {
            log.warn("scheduled refresh failed: {}", e.getMessage());
        }
    }

    public String fallbackAsOf() {
        return fallback.asOf();
    }
}
