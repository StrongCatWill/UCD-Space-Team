package ie.ucd.spaceteam.firecal.api.store;

import ie.ucd.spaceteam.firecal.contract.api.DevicePayload;
import ie.ucd.spaceteam.firecal.contract.api.ForecastData;
import ie.ucd.spaceteam.firecal.contract.data.Calibration;
import ie.ucd.spaceteam.firecal.contract.data.Coverage;
import ie.ucd.spaceteam.firecal.contract.data.DemoArea;
import ie.ucd.spaceteam.firecal.contract.data.DemoFile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of everything loaded from the data directory. Swapped atomically by {@link DataStoreHolder}.
 * mockForecasts is only filled in the mock profile (real forecasts are computed).
 */
public record DataStore(
        String location,
        Instant loadedAt,
        Calibration calibration,
        Coverage coverage,
        List<DemoArea> demoAreas,
        Map<String, DemoFile> demoFiles,
        DevicePayload snapshot,
        Map<String, ForecastData> mockForecasts) {

    public DataStore {
        demoAreas = List.copyOf(demoAreas);
        demoFiles = Map.copyOf(demoFiles);
        mockForecasts = Map.copyOf(mockForecasts);
    }

    public Optional<DemoArea> demoArea(String areaId) {
        return demoAreas.stream().filter(a -> a.areaId().equals(areaId)).findFirst();
    }

    public String harmonizationVersion() {
        return calibration.harmonizationVersion();
    }
}
