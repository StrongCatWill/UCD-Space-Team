package ie.ucd.space.firecal.api.outlook;

import com.fasterxml.jackson.databind.JsonNode;
import ie.ucd.space.firecal.contract.api.OutlookLevel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;

/**
 * Parses the SPC_firewx MapServer layer query (f=geojson). Verified 2026-11-14 against
 * .../SPC_firewx/MapServer?f=json and each layer's drawingInfo renderer (field "dn"):
 *   layers 1 (Day 1) and 4 (Day 2) "Outlook":         dn 5 Elevated, 8 Critical, 10 Extreme
 *   layers 2 (Day 1) and 5 (Day 2) "Dry Thunderstorm": dn 5 Iso DryT, 8 Scattered DryT
 *   dn 0 = no area (geometry null). valid/expire = yyyyMMddHHmm UTC; idp_filedate = epoch ms.
 * Unknown layers or dn values fail loudly.
 */
public final class SpcParser {
    private SpcParser() {}

    private enum Kind { CATEGORICAL, DRY_THUNDER }

    private record Layer(String product, Kind kind) {}

    static final Map<Integer, Layer> LAYERS = Map.of(
            1, new Layer("Day 1 Fire Weather Outlook", Kind.CATEGORICAL),
            2, new Layer("Day 1 Dry Thunderstorm Outlook", Kind.DRY_THUNDER),
            4, new Layer("Day 2 Fire Weather Outlook", Kind.CATEGORICAL),
            5, new Layer("Day 2 Dry Thunderstorm Outlook", Kind.DRY_THUNDER));

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    public static void checkLayer(int layer) {
        if (!LAYERS.containsKey(layer)) {
            throw new IllegalStateException("SPC layer " + layer + " is not a verified Day 1/2 polygon layer " + LAYERS.keySet());
        }
    }

    public static List<SpcFeature> parse(int layerId, JsonNode root) {
        checkLayer(layerId);
        Layer layer = LAYERS.get(layerId);
        if (root.has("error")) {
            throw new IllegalStateException("SPC layer " + layerId + " error: " + root.get("error").path("message").asText());
        }
        JsonNode features = root.get("features");
        if (features == null || !features.isArray()) {
            throw new IllegalStateException("SPC layer " + layerId + ": response has no features array");
        }
        GeoJsonReader reader = new GeoJsonReader();
        List<SpcFeature> out = new ArrayList<>();
        for (JsonNode f : features) {
            JsonNode p = f.path("properties");
            int dn = p.path("dn").asInt(0);
            if (dn == 0 || f.path("geometry").isNull() || f.path("geometry").isMissingNode()) continue;
            OutlookLevel level;
            String label;
            switch (layer.kind()) {
                case CATEGORICAL -> {
                    switch (dn) {
                        case 5 -> { level = OutlookLevel.ELEVATED; label = "Elevated"; }
                        case 8 -> { level = OutlookLevel.CRITICAL; label = "Critical"; }
                        case 10 -> { level = OutlookLevel.EXTREMELY_CRITICAL; label = "Extremely Critical"; }
                        default -> throw unknown(layerId, dn);
                    }
                }
                case DRY_THUNDER -> {
                    switch (dn) {
                        case 5 -> { level = OutlookLevel.ELEVATED; label = "Isolated Dry Thunderstorms"; }
                        case 8 -> { level = OutlookLevel.CRITICAL; label = "Scattered Dry Thunderstorms"; }
                        default -> throw unknown(layerId, dn);
                    }
                }
                default -> throw unknown(layerId, dn);
            }
            Instant validTo = ts(p.path("expire").asText(null));
            Instant issued = p.hasNonNull("idp_filedate") ? Instant.ofEpochMilli(p.get("idp_filedate").asLong())
                    : ts(p.path("valid").asText(null));
            try {
                out.add(new SpcFeature(layer.product(), level, label, issued, validTo, reader.read(f.get("geometry").toString())));
            } catch (ParseException e) {
                throw new IllegalStateException("SPC layer " + layerId + ": bad geometry: " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static IllegalStateException unknown(int layer, int dn) {
        return new IllegalStateException("SPC layer " + layer + ": unknown dn=" + dn);
    }

    private static Instant ts(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDateTime.parse(s.trim(), TS).toInstant(ZoneOffset.UTC);
    }
}
