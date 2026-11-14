package ie.ucd.space.firecal.api.web;

import ie.ucd.space.firecal.api.config.FireProperties;
import ie.ucd.space.firecal.api.data.DataStoreHolder;
import io.swagger.v3.oas.annotations.Operation;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

    private final DataStoreHolder holder;
    private final FireProperties props;

    public AdminController(DataStoreHolder holder, FireProperties props) {
        this.holder = holder;
        this.props = props;
    }

    @Operation(summary = "Reload out/ atomically; on failure the previous data stays live")
    @PostMapping("/admin/reload")
    public DataStoreHolder.ReloadResult reload(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        String expected = props.adminToken();
        if (expected != null && !expected.isBlank()
                && (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))) {
            throw new ApiException(ie.ucd.space.firecal.contract.api.ErrorCode.BAD_INPUT, HttpStatus.FORBIDDEN, "admin token required");
        }
        return holder.reload(null);
    }
}
