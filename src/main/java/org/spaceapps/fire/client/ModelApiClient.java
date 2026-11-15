package org.spaceapps.fire.client;

import java.util.Map;
import org.spaceapps.fire.config.FireProperties;
import org.spaceapps.fire.dto.ForecastRequest;
import org.spaceapps.fire.dto.ForecastResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin client for the Python fire-service. Every method throws RestClientException when the
 * service is down or answers 4xx/5xx; callers decide on the fallback.
 */
@Component
public class ModelApiClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {};

    private final RestClient http;
    private final FireProperties props;

    public ModelApiClient(RestClient modelApiRestClient, FireProperties props) {
        this.http = modelApiRestClient;
        this.props = props;
    }

    public ForecastResponse forecast(ForecastRequest request) {
        return http.post().uri("/forecast")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ForecastResponse.class);
    }

    public Map<String, Object> accuracy(String from, String to, String bbox) {
        return http.get().uri(b -> {
                    b.path("/accuracy");
                    if (from != null) b.queryParam("from", from);
                    if (to != null) b.queryParam("to", to);
                    if (bbox != null) b.queryParam("bbox", bbox);
                    return b.build();
                })
                .retrieve()
                .body(JSON_MAP);
    }

    /** Raw FIRMS VIIRS points for the optional point view (fetched live by the Python service). */
    public Map<String, Object> detections(String bbox, String date, Integer days) {
        return http.get().uri(b -> {
                    b.path("/detections").queryParam("bbox", bbox);
                    if (date != null) b.queryParam("date", date);
                    if (days != null) b.queryParam("days", days);
                    return b.build();
                })
                .retrieve()
                .body(JSON_MAP);
    }

    public Map<String, Object> observed(String date, String bbox) {
        return http.get().uri(b -> {
                    b.path("/observed").queryParam("date", date);
                    if (bbox != null) b.queryParam("bbox", bbox);
                    return b.build();
                })
                .retrieve()
                .body(JSON_MAP);
    }

    public Map<String, Object> health() {
        return http.get().uri("/health").retrieve().body(JSON_MAP);
    }

    /** Ask the Python service to pull new days from its satellite plugins (FIRMS etc.). */
    public Map<String, Object> ingest(String start, String end) {
        var body = new java.util.HashMap<String, Object>();
        if (start != null) body.put("start", start);
        if (end != null) body.put("end", end);
        String token = props.modelApi().ingestToken() == null ? "" : props.modelApi().ingestToken();
        return http.post().uri("/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Ingest-Token", token)
                .body(body)
                .retrieve()
                .body(JSON_MAP);
    }
}
