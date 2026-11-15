package org.spaceapps.fire.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FireProperties props;

    public WebConfig(FireProperties props) {
        this.props = props;
    }

    /**
     * RestClient for the Python model API. Built from Boot's auto-configured builder so it picks up
     * the JSON converters and the spring.http.clients.* timeouts.
     */
    @Bean
    RestClient modelApiRestClient(RestClient.Builder builder) {
        builder.baseUrl(props.modelApi().url())
                .defaultHeader("ngrok-skip-browser-warning", "true"); // harmless unless the API sits behind ngrok
        if ("gcp-id-token".equals(props.modelApi().auth())) {
            builder.requestInterceptor(new GcpIdTokenInterceptor(props.modelApi().url()));
        }
        return builder.build();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST");
    }
}
