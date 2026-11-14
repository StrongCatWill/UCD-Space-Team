package ie.ucd.spaceteam.firecal.api.web;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** MOCK for now: returns a transparent 1x1 PNG. The real FIRMS WMS proxy (layer whitelist, key) is D2 task 8. */
@RestController
public class WmsController {

    private static final Pattern LAYER = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final byte[] TRANSPARENT_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Operation(summary = "FIRMS WMS proxy (mock: transparent tile)")
    @GetMapping(value = "/api/wms/{layer}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> wms(@PathVariable("layer") String layer) {
        if (!LAYER.matcher(layer).matches()) {
            throw ApiException.badInput("unknown WMS layer");
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(TRANSPARENT_PNG);
    }
}
