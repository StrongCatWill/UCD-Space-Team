package ie.ucd.space.firecal.api.outlook;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** SPC Fire Weather Outlook polygons from the NOAA MapServer (no key). */
public class SpcClient implements OutlookSource {

    private final RestClient http;
    private final List<Integer> layers;

    public SpcClient(RestClient.Builder builder, String baseUrl, List<Integer> layers) {
        layers.forEach(SpcParser::checkLayer);
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        rf.setReadTimeout(Duration.ofSeconds(20));
        this.http = builder.baseUrl(baseUrl).requestFactory(rf).build();
        this.layers = List.copyOf(layers);
    }

    @Override
    public List<SpcFeature> fetchAll() {
        List<SpcFeature> all = new ArrayList<>();
        for (int layer : layers) {
            JsonNode body = http.get()
                    .uri(u -> u.path("/{layer}/query")
                            .queryParam("where", "1=1")
                            .queryParam("outFields", "dn,valid,expire,idp_filedate")
                            .queryParam("returnGeometry", "true")
                            .queryParam("f", "geojson")
                            .build(layer))
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) throw new IllegalStateException("SPC layer " + layer + ": empty response");
            all.addAll(SpcParser.parse(layer, body));
        }
        return all;
    }
}
