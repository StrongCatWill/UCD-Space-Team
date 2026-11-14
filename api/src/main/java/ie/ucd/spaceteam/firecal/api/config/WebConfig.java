package ie.ucd.spaceteam.firecal.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import ie.ucd.spaceteam.firecal.contract.FireJson;
import io.swagger.v3.core.jackson.ModelResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(FireProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final FireProperties props;

    public WebConfig(FireProperties props) {
        this.props = props;
    }

    /** Same Jackson settings as the pipeline (snake_case, ISO dates, strict on unknown fields). */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return FireJson.configure(new ObjectMapper());
    }

    /** Makes springdoc emit snake_case property names in /v3/api-docs (frontend types come from it). */
    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(props.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition");
    }
}
