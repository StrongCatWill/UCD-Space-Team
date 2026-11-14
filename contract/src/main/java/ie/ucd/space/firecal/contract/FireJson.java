package ie.ucd.space.firecal.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one shared ObjectMapper configuration for files in out/ and the REST API:
 * snake_case, java.time as ISO strings (YearMonth -> "2026-11", Instant -> "2026-11-14T08:00:00Z").
 */
public final class FireJson {
    private FireJson() {}

    public static JsonMapper.Builder builder() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static ObjectMapper mapper() {
        return builder().build();
    }
}
