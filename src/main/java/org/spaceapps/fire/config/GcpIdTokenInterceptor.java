package org.spaceapps.fire.config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Adds a Google-signed ID token (audience = the fire-service URL) so the backend can call a private Cloud Run
 * service. The token comes from the Cloud Run metadata server; it is valid for 1 hour, refreshed after 50 minutes.
 */
final class GcpIdTokenInterceptor implements ClientHttpRequestInterceptor {

    private static final String METADATA =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=";

    private final String audience;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String token;
    private Instant expires = Instant.EPOCH;

    GcpIdTokenInterceptor(String audience) {
        this.audience = audience;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token());
        return execution.execute(request, body);
    }

    private synchronized String token() throws IOException {
        if (token == null || Instant.now().isAfter(expires)) {
            var req = java.net.http.HttpRequest.newBuilder(
                            URI.create(METADATA + URLEncoder.encode(audience, StandardCharsets.UTF_8)))
                    .header("Metadata-Flavor", "Google").timeout(Duration.ofSeconds(3)).GET().build();
            try {
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) throw new IOException("metadata server answered " + r.statusCode());
                token = r.body().trim();
                expires = Instant.now().plus(Duration.ofMinutes(50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while fetching ID token", e);
            }
        }
        return token;
    }
}
