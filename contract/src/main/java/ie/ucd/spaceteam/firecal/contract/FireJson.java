package ie.ucd.spaceteam.firecal.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** The one ObjectMapper configuration shared by pipeline and api. */
public final class FireJson {
    private static final ObjectMapper MAPPER = configure(new ObjectMapper());

    private FireJson() {}

    /** Shared, thread-safe instance. Do not reconfigure it. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Applies the contract settings to an existing mapper (e.g. Spring's). */
    public static ObjectMapper configure(ObjectMapper m) {
        return m.registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
