package org.spaceapps.fire.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AdminTokenTest {

    @Test
    void emptyConfiguredTokenDisablesEndpoint() {
        assertThatThrownBy(() -> AdminToken.require("", "anything"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }

    @Test
    void wrongOrMissingTokenIsRejected() {
        assertThatThrownBy(() -> AdminToken.require("s3cret", "nope")).hasMessageContaining("401");
        assertThatThrownBy(() -> AdminToken.require("s3cret", null)).hasMessageContaining("401");
    }

    @Test
    void matchingTokenPasses() {
        assertThatCode(() -> AdminToken.require("s3cret", "s3cret")).doesNotThrowAnyException();
    }
}
