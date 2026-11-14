package ie.ucd.space.firecal.api.wms;

import ie.ucd.space.firecal.api.web.ApiException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Proxies FIRMS WMS: GET {base}/{FIRMS_KEY}/{layer}/?{WMS params}. The key never leaves the server: it is not logged,
 * not echoed in errors and not returned. Only whitelisted layers and standard WMS parameters are forwarded.
 * Image responses are cached briefly (LRU, TTL).
 */
public class WmsProxy {

    public record Tile(String contentType, byte[] body) {}

    @FunctionalInterface
    public interface Fetcher {
        Tile fetch(URI uri) throws Exception;
    }

    private record Cached(Tile tile, Instant expires) {}

    static final Set<String> WMS_PARAMS = Set.of("SERVICE", "VERSION", "REQUEST", "LAYERS", "STYLES", "CRS", "SRS",
            "BBOX", "WIDTH", "HEIGHT", "FORMAT", "TRANSPARENT", "BGCOLOR", "TIME", "EXCEPTIONS", "SYMBOLS", "COLORS", "SIZE");

    private static final Logger log = LoggerFactory.getLogger(WmsProxy.class);
    private final String baseUrl;
    private final String key;
    private final Set<String> layers;
    private final Duration ttl;
    private final Fetcher fetcher;
    private final Map<String, Cached> cache;

    public WmsProxy(String baseUrl, String key, List<String> layers, Duration ttl, int maxEntries, Fetcher fetcher) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.key = key;
        this.layers = Set.copyOf(layers);
        this.ttl = ttl;
        this.fetcher = fetcher;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> e) {
                return size() > maxEntries;
            }
        };
    }

    public Tile get(String layer, Map<String, String> clientParams) {
        if (!layers.contains(layer)) throw ApiException.badInput("layer not allowed: " + layer);
        if (key == null || key.isBlank()) throw ApiException.upstream("FIRMS WMS unavailable: FIRMS_KEY is not configured");

        Map<String, String> params = new TreeMap<>();
        clientParams.forEach((k, v) -> {
            String up = k.toUpperCase(Locale.ROOT);
            if (WMS_PARAMS.contains(up) && v != null) params.put(up, v);
        });
        String cacheKey = layer + "?" + params;

        synchronized (cache) {
            Cached c = cache.get(cacheKey);
            if (c != null && c.expires().isAfter(Instant.now())) return c.tile();
        }

        // spec: .../wms/fires/{FIRMS_KEY}/{layer}/  (trailing slash required)
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl).pathSegment(key, layer).path("/");
        params.forEach(b::queryParam);
        URI uri = b.encode().build().toUri();
        Tile tile;
        try {
            tile = fetcher.fetch(uri);
        } catch (Exception e) {
            // the exception text may contain the URL (and therefore the key): never log or return it
            log.warn("FIRMS WMS request failed for layer {} ({})", layer, e.getClass().getSimpleName());
            throw ApiException.upstream("FIRMS WMS unavailable");
        }
        if (tile.contentType() != null && tile.contentType().startsWith("image/")) {
            synchronized (cache) {
                cache.put(cacheKey, new Cached(tile, Instant.now().plus(ttl)));
            }
        }
        return tile;
    }
}
