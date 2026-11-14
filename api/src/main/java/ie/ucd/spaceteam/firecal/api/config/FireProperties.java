package ie.ucd.spaceteam.firecal.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dataLocation    Spring resource prefix of the data files: "classpath:mock/" (mock) or "file:out/" (real)
 * @param demoAreas       resource of the demo area list
 * @param corsOrigins     allowed browser origins (frontend dev servers)
 * @param anomalyAlertPct device alert when anomaly_pct >= this
 * @param alertLevels     official outlook levels that also trigger the device alert
 */
@ConfigurationProperties(prefix = "fire")
public record FireProperties(
        String dataLocation,
        String demoAreas,
        List<String> corsOrigins,
        double anomalyAlertPct,
        List<String> alertLevels) {}
