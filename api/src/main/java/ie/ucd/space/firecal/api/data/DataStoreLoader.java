package ie.ucd.space.firecal.api.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import ie.ucd.space.firecal.contract.FireJson;
import ie.ucd.space.firecal.contract.api.DevicePayload;
import ie.ucd.space.firecal.contract.files.Calibration;
import ie.ucd.space.firecal.contract.files.CalibrationFactor;
import ie.ucd.space.firecal.contract.files.CellMonth;
import ie.ucd.space.firecal.contract.files.Coverage;
import ie.ucd.space.firecal.contract.files.DemoAreaConfig;
import ie.ucd.space.firecal.contract.files.DemoAreaFile;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Reads out/ (or classpath:mock/) into a {@link DataStore}. Required: cell_month.json, calibration.json,
 * coverage.json, demo areas config. Optional: demo/{area_id}.json, demo_snapshot.json.
 * cell_month.json is streamed element by element.
 */
@Component
public class DataStoreLoader {

    private static final Logger log = LoggerFactory.getLogger(DataStoreLoader.class);
    private final ResourceLoader resources;
    private final ObjectMapper json = FireJson.mapper();

    public DataStoreLoader(ResourceLoader resources) {
        this.resources = resources;
    }

    public DataStore load(String dataDir, String demoAreasLocation) {
        String dir = dataDir.endsWith("/") ? dataDir : dataDir + "/";
        Calibration calibration = read(dir + "calibration.json", Calibration.class);
        Coverage coverage = read(dir + "coverage.json", Coverage.class);
        List<DemoAreaConfig> demoAreas = read(demoAreasLocation, new TypeReference<List<DemoAreaConfig>>() {});
        requireNonNull(calibration.switchMonth(), "calibration.json: switch_month");
        requireNonNull(calibration.seriesStart(), "calibration.json: series_start");
        requireNonNull(coverage.modisAqua(), "coverage.json: MODIS_AQUA");
        requireNonNull(coverage.viirsNoaa20(), "coverage.json: VIIRS_NOAA20");

        Map<String, DemoAreaFile> demoFiles = new HashMap<>();
        for (DemoAreaConfig area : demoAreas) {
            Resource r = resources.getResource(dir + "demo/" + area.areaId() + ".json");
            if (r.exists()) demoFiles.put(area.areaId(), read(r, DemoAreaFile.class));
        }
        Resource snapR = resources.getResource(dir + "demo_snapshot.json");
        DevicePayload snapshot = snapR.exists() ? read(snapR, DevicePayload.class) : null;

        Map<String, CalibrationFactor> factors = new HashMap<>();
        for (List<CalibrationFactor> level : List.of(nz(calibration.regions()), nz(calibration.country()), nz(calibration.northAmerica()))) {
            for (CalibrationFactor f : level) factors.putIfAbsent(DataStore.key(f.regionId(), f.month()), f);
        }

        // cell months: stream, intern cell ids, index by cell and by month
        Resource cellsR = resources.getResource(dir + "cell_month.json");
        Map<String, DataStore.CellRef> cellRefs = new LinkedHashMap<>();
        Map<String, List<CellMonth>> byCell = new HashMap<>();
        Map<YearMonth, List<CellMonth>> byYm = new HashMap<>();
        YearMonth maxYm = null;
        boolean anyProvisional = false;
        int count = 0;
        try (InputStream in = open(cellsR, "cell_month.json");
             MappingIterator<CellMonth> it = json.readerFor(CellMonth.class).readValues(in)) {
            while (it.hasNext()) {
                CellMonth cm = it.next();
                if (cm.cellId() == null || cm.ym() == null) {
                    throw new DataLoadException("cell_month.json: element " + count + " lacks cell_id or ym", null);
                }
                DataStore.CellRef ref = cellRefs.computeIfAbsent(cm.cellId(),
                        id -> new DataStore.CellRef(id, cm.lat(), cm.lon(), cm.parent()));
                byCell.computeIfAbsent(ref.cellId(), k -> new ArrayList<>()).add(cm);
                byYm.computeIfAbsent(cm.ym(), k -> new ArrayList<>()).add(cm);
                if (maxYm == null || cm.ym().isAfter(maxYm)) maxYm = cm.ym();
                anyProvisional |= cm.provisional();
                count++;
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof DataLoadException d) throw d;
            throw new DataLoadException("cell_month.json: cannot parse (" + e.getMessage() + ")", e);
        }
        byCell.replaceAll((k, v) -> v.stream().sorted(Comparator.comparing(CellMonth::ym)).toList());
        byYm.replaceAll((k, v) -> List.copyOf(v));

        // real out/: when the pipeline last wrote cell_month.json; classpath mock: the file's generated_at
        Instant asOf = dir.startsWith("file:")
                ? lastModified(cellsR).orElse(calibration.generatedAt())
                : calibration.generatedAt();
        log.info("Loaded data from {}: {} cell-months, {} cells, months up to {}, {} demo files, version {}",
                dir, count, cellRefs.size(), maxYm, demoFiles.size(), calibration.harmonizationVersion());
        return new DataStore(dir, asOf, calibration, coverage, demoAreas, demoFiles, snapshot,
                new ArrayList<>(cellRefs.values()), byCell, byYm, factors, count, maxYm, anyProvisional);
    }

    private <T> T read(String location, Class<T> type) {
        return read(resources.getResource(location), type);
    }

    private <T> T read(Resource r, Class<T> type) {
        try (InputStream in = open(r, name(r))) {
            return json.readValue(in, type);
        } catch (IOException e) {
            throw new DataLoadException(name(r) + ": cannot parse (" + e.getMessage() + ")", e);
        }
    }

    private <T> T read(String location, TypeReference<T> type) {
        Resource r = resources.getResource(location);
        try (InputStream in = open(r, location)) {
            return json.readValue(in, type);
        } catch (IOException e) {
            throw new DataLoadException(location + ": cannot parse (" + e.getMessage() + ")", e);
        }
    }

    private static InputStream open(Resource r, String what) {
        if (!r.exists()) throw new DataLoadException(what + ": required file not found (" + r.getDescription() + ")", null);
        try {
            return r.getInputStream();
        } catch (IOException e) {
            throw new DataLoadException(what + ": cannot open (" + e.getMessage() + ")", e);
        }
    }

    private static java.util.Optional<Instant> lastModified(Resource r) {
        try {
            return r.isFile() ? java.util.Optional.of(Instant.ofEpochMilli(r.lastModified())) : java.util.Optional.empty();
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static String name(Resource r) {
        return Objects.requireNonNullElse(r.getFilename(), r.getDescription());
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static void requireNonNull(Object o, String what) {
        if (o == null) throw new DataLoadException(what + " is missing", null);
    }
}
