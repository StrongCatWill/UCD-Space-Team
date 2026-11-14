package ie.ucd.space.firecal.api.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ie.ucd.space.firecal.api.web.ApiException;
import ie.ucd.space.firecal.contract.api.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WmsProxyTest {

    static final String KEY = "SECRETKEY123";
    final List<URI> calls = new ArrayList<>();

    private WmsProxy proxy(String key, WmsProxy.Fetcher fetcher) {
        return new WmsProxy("https://firms2.modaps.eosdis.nasa.gov/mapserver/wms/fires", key,
                List.of("fires_viirs_noaa20_24"), Duration.ofMinutes(5), 100, fetcher);
    }

    private WmsProxy.Fetcher png() {
        return uri -> {
            calls.add(uri);
            return new WmsProxy.Tile("image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        };
    }

    private static Map<String, String> params() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("SERVICE", "WMS");
        p.put("REQUEST", "GetMap");
        p.put("BBOX", "-125,35,-115,45");
        p.put("WIDTH", "256");
        p.put("HEIGHT", "256");
        p.put("FORMAT", "image/png");
        p.put("callback", "evil()");
        return p;
    }

    @Test
    void forwardsWhitelistedLayerWithKeyInPathAndDropsUnknownParams() {
        WmsProxy.Tile t = proxy(KEY, png()).get("fires_viirs_noaa20_24", params());
        assertThat(t.contentType()).isEqualTo("image/png");
        URI u = calls.get(0);
        assertThat(u.toString()).startsWith("https://firms2.modaps.eosdis.nasa.gov/mapserver/wms/fires/" + KEY + "/fires_viirs_noaa20_24/?");
        assertThat(u.getQuery()).contains("REQUEST=GetMap").contains("BBOX=-125,35,-115,45").doesNotContain("callback");
    }

    @Test
    void cachesTilesBriefly() {
        WmsProxy p = proxy(KEY, png());
        p.get("fires_viirs_noaa20_24", params());
        p.get("fires_viirs_noaa20_24", params());
        assertThat(calls).hasSize(1);
    }

    @Test
    void rejectsLayerNotInWhitelist() {
        assertThatThrownBy(() -> proxy(KEY, png()).get("fires_snpp_24", params()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.BAD_INPUT));
    }

    @Test
    void missingKeyOrUpstreamFailureIs503WithoutLeakingKey() {
        assertThatThrownBy(() -> proxy("", png()).get("fires_viirs_noaa20_24", params()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
        WmsProxy.Fetcher boom = uri -> { throw new RuntimeException("I/O error on GET request for \"" + uri + "\""); };
        assertThatThrownBy(() -> proxy(KEY, boom).get("fires_viirs_noaa20_24", params()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain(KEY);
                });
    }

    @Test
    void nonImageResponsesAreNotCached() {
        WmsProxy.Fetcher xml = uri -> {
            calls.add(uri);
            return new WmsProxy.Tile("application/vnd.ogc.se_xml", "<ServiceException/>".getBytes());
        };
        WmsProxy p = proxy(KEY, xml);
        p.get("fires_viirs_noaa20_24", params());
        p.get("fires_viirs_noaa20_24", params());
        assertThat(calls).hasSize(2);
    }
}
