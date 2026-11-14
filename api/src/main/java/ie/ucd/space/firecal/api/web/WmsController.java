package ie.ucd.space.firecal.api.web;

import ie.ucd.space.firecal.api.wms.WmsProxy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class WmsController {

    private final WmsProxy proxy;

    public WmsController(WmsProxy proxy) {
        this.proxy = proxy;
    }

    @Operation(summary = "FIRMS WMS proxy (key stays server-side); pass standard WMS query parameters",
            responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "image/png")))
    @GetMapping("/api/wms/{layer}")
    public ResponseEntity<byte[]> wms(@PathVariable("layer") @Pattern(regexp = "[a-z0-9_]{1,60}") String layer,
                                      @RequestParam Map<String, String> params) {
        WmsProxy.Tile t = proxy.get(layer, params);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(t.contentType() == null ? "application/octet-stream" : t.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                .header("X-Credit", "NASA FIRMS")
                .body(t.body());
    }
}
