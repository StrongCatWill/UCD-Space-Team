package ie.ucd.space.firecal.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import ie.ucd.space.firecal.contract.FireJson;
import io.swagger.v3.core.jackson.ModelResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    static {
        // openapi-typescript must see months as "string", not an object
        SpringDocUtils.getConfig().replaceWithClass(YearMonth.class, String.class);
    }

    private final FireProperties props;

    public AppConfig(FireProperties props) {
        this.props = props;
    }

    /** Same mapper as the pipeline uses for out/: API JSON and files serialize identically. */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return FireJson.mapper();
    }

    /** Makes springdoc render schemas with the snake_case naming strategy. */
    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }

    @Bean
    public Clock clock() {
        String fixed = props.clockFixed();
        return (fixed == null || fixed.isBlank())
                ? Clock.systemUTC()
                : Clock.fixed(Instant.parse(fixed), ZoneOffset.UTC);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .exposedHeaders("Content-Disposition", "X-Harmonization-Version", "X-Credit");
    }
}
