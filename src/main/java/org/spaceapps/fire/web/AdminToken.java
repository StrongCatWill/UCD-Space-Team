package org.spaceapps.fire.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Guards the public admin endpoint once the API is deployed. */
final class AdminToken {

    private AdminToken() {}

    static void require(String configured, String provided) {
        if (configured == null || configured.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin refresh disabled: set FIRE_ADMIN_TOKEN");
        }
        if (provided == null || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing or wrong X-Admin-Token");
        }
    }
}
