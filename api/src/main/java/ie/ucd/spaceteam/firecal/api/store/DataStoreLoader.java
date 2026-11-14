package ie.ucd.spaceteam.firecal.api.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ie.ucd.spaceteam.firecal.api.config.FireProperties;
import ie.ucd.spaceteam.firecal.contract.api.DevicePayload;
import ie.ucd.spaceteam.firecal.contract.api.ForecastData;
import ie.ucd.spaceteam.firecal.contract.data.Calibration;
import ie.ucd.spaceteam.firecal.contract.data.Coverage;
import ie.ucd.spaceteam.firecal.contract.data.DemoArea;
import ie.ucd.spaceteam.firecal.contract.data.DemoFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Reads the data directory (mock classpath or real out/) into a {@link DataStore}. Fails loudly on any bad file. */
@Component
public class DataStoreLoader {

    private final ResourceLoader resources;
    private final ObjectMapper json;
    private final FireProperties props;
    private final Clock clock;

    public DataStoreLoader(ResourceLoader resources, ObjectMapper json, FireProperties props, Clock clock) {
        this.resources = resources;
        this.json = json;
        this.props = props;
        this.clock = clock;
    }

    public DataStore load() {
        String base = props.dataLocation();
        Calibration calibration = read(base + "calibration.json", new TypeReference<>() {});
        Coverage coverage = read(base + "coverage.json", new TypeReference<>() {});
        List<DemoArea> areas = read(props.demoAreas(), new TypeReference<>() {});
        Map<String, DemoFile> demo = new HashMap<>();
        Map<String, ForecastData> forecasts = new HashMap<>();
        for (DemoArea a : areas) {
            demo.put(a.areaId(), read(base + "demo/" + a.areaId() + ".json", new TypeReference<>() {}));
            Resource f = resource(base + "forecast/" + a.areaId() + ".json");
            if (f.exists()) {
                forecasts.put(a.areaId(), read(f, new TypeReference<>() {}));
            }
        }
        DevicePayload snapshot = read(base + "demo_snapshot.json", new TypeReference<>() {});
        return new DataStore(base, clock.instant(), calibration, coverage, areas, demo, snapshot, forecasts);
    }

    public Resource resource(String location) {
        return resources.getResource(location);
    }

    private <T> T read(String location, TypeReference<T> type) {
        return read(resource(location), type);
    }

    private <T> T read(Resource r, TypeReference<T> type) {
        try (InputStream in = r.getInputStream()) {
            return json.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load " + r.getDescription() + ": " + e.getMessage(), e);
        }
    }
}
