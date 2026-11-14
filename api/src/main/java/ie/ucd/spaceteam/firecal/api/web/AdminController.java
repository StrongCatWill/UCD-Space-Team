package ie.ucd.spaceteam.firecal.api.web;

import ie.ucd.spaceteam.firecal.api.store.DataStore;
import ie.ucd.spaceteam.firecal.api.store.DataStoreHolder;
import ie.ucd.spaceteam.firecal.contract.api.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

    /** Admin-only response (not used by the frontend). */
    public record ReloadResult(String location, String harmonizationVersion, Instant loadedAt) {}

    private final DataStoreHolder holder;

    public AdminController(DataStoreHolder holder) {
        this.holder = holder;
    }

    @Operation(summary = "Reload data files; on failure the previous data stays active")
    @PostMapping("/admin/reload")
    public ReloadResult reload() {
        try {
            DataStore s = holder.reload();
            return new ReloadResult(s.location(), s.harmonizationVersion(), s.loadedAt());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "reload failed, previous data kept: " + e.getMessage());
        }
    }
}
