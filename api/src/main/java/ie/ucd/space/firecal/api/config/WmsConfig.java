package ie.ucd.space.firecal.api.config;

import ie.ucd.space.firecal.api.wms.WmsProxy;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WmsConfig {

    @Bean
    public WmsProxy wmsProxy(RestClient.Builder builder, FireProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        rf.setReadTimeout(Duration.ofSeconds(30));
        RestClient http = builder.clone().requestFactory(rf).build();
        FireProperties.Wms w = props.wms();
        return new WmsProxy(w.baseUrl(), w.firmsKey(), w.layers(), w.cacheTtl(), w.cacheMaxEntries(), uri -> {
            ResponseEntity<byte[]> r = http.get().uri(uri).retrieve().toEntity(byte[].class);
            String type = r.getHeaders().getContentType() == null ? null : r.getHeaders().getContentType().toString();
            return new WmsProxy.Tile(type, r.getBody() == null ? new byte[0] : r.getBody());
        });
    }
}
