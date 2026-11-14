package ie.ucd.space.firecal.api.config;

import ie.ucd.space.firecal.contract.YearRange;
import ie.ucd.space.firecal.contract.api.OutlookLevel;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All api settings under "fire.*" (see application.yml). */
@ConfigurationProperties("fire")
public record FireProperties(
        String dataDir,
        String demoAreas,
        String clockFixed,
        String adminToken,
        YearRange baseline,
        double maxAreaKm2,
        double naToleranceDeg,
        int hotspotRowLimit,
        Alert alert,
        Cors cors,
        Spc spc,
        Wms wms) {

    public record Alert(double anomalyPct, OutlookLevel officialLevel) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Spc(boolean enabled, String baseUrl, List<Integer> layers, Duration refresh) {}

    public record Wms(String baseUrl, String firmsKey, List<String> layers, Duration cacheTtl, int cacheMaxEntries) {}
}
