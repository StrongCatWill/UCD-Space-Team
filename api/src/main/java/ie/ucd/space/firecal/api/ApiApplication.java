package ie.ucd.space.firecal.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** D2: Harmonized Fire Activity Calendar REST API. Profiles: mock (default) | real. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
