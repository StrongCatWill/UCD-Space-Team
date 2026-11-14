package ie.ucd.space.firecal.api.data;

import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.contract.files.Calibration;
import ie.ucd.space.firecal.contract.files.CalibrationFactor;
import ie.ucd.space.firecal.contract.files.CellMonth;
import ie.ucd.space.firecal.contract.files.Coverage;
import ie.ucd.space.firecal.contract.files.DemoAreaConfig;
import ie.ucd.space.firecal.contract.files.DemoAreaFile;
import ie.ucd.space.firecal.contract.api.DevicePayload;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of everything the API serves. Built by {@link DataStoreLoader}, swapped atomically by
 * {@link DataStoreHolder}. Cell months are indexed by cell (for AOI sums) and by month.
 */
public final class DataStore {

    /** One distinct 0.1 deg cell (centre coordinates). */
    public record CellRef(String cellId, double lat, double lon, Country parent) {}

    private final String dataDir;
    private final Instant asOf;
    private final Calibration calibration;
    private final Coverage coverage;
    private final List<DemoAreaConfig> demoAreas;
    private final Map<String, DemoAreaFile> demoFiles;
    private final DevicePayload snapshot;
    private final List<CellRef> cells;
    private final Map<String, List<CellMonth>> byCell;
    private final Map<YearMonth, List<CellMonth>> byYm;
    private final Map<String, CalibrationFactor> factors;
    private final int cellMonthCount;
    private final YearMonth maxYm;
    private final boolean anyProvisional;

    DataStore(String dataDir, Instant asOf, Calibration calibration, Coverage coverage,
              List<DemoAreaConfig> demoAreas, Map<String, DemoAreaFile> demoFiles, DevicePayload snapshot,
              List<CellRef> cells, Map<String, List<CellMonth>> byCell, Map<YearMonth, List<CellMonth>> byYm,
              Map<String, CalibrationFactor> factors, int cellMonthCount, YearMonth maxYm, boolean anyProvisional) {
        this.dataDir = dataDir;
        this.asOf = asOf;
        this.calibration = calibration;
        this.coverage = coverage;
        this.demoAreas = List.copyOf(demoAreas);
        this.demoFiles = Map.copyOf(demoFiles);
        this.snapshot = snapshot;
        this.cells = List.copyOf(cells);
        this.byCell = Map.copyOf(byCell);
        this.byYm = Map.copyOf(byYm);
        this.factors = Map.copyOf(factors);
        this.cellMonthCount = cellMonthCount;
        this.maxYm = maxYm;
        this.anyProvisional = anyProvisional;
    }

    public String dataDir() { return dataDir; }
    public Instant asOf() { return asOf; }
    public Calibration calibration() { return calibration; }
    public Coverage coverage() { return coverage; }
    public List<DemoAreaConfig> demoAreas() { return demoAreas; }
    public DevicePayload snapshot() { return snapshot; }
    public List<CellRef> cells() { return cells; }
    public int cellMonthCount() { return cellMonthCount; }
    public YearMonth maxYm() { return maxYm; }
    public boolean anyProvisional() { return anyProvisional; }

    public List<CellMonth> monthsOf(String cellId) {
        return byCell.getOrDefault(cellId, List.of());
    }

    public List<CellMonth> cellsIn(YearMonth ym) {
        return byYm.getOrDefault(ym, List.of());
    }

    public Optional<DemoAreaConfig> demoArea(String areaId) {
        return demoAreas.stream().filter(a -> a.areaId().equals(areaId)).findFirst();
    }

    public Optional<DemoAreaFile> demoFile(String areaId) {
        return Optional.ofNullable(demoFiles.get(areaId));
    }

    /** Calibration factor for a cell centre and calendar month: 5 deg block, then country, then North America. */
    public Optional<CalibrationFactor> factorFor(double lat, double lon, Country parent, int month) {
        CalibrationFactor f = factors.get(key(regionId(lat, lon), month));
        if (f == null && parent != null) f = factors.get(key(parent.name(), month));
        if (f == null) f = factors.get(key("NA", month));
        return Optional.ofNullable(f);
    }

    /** region_id = "B_" + floorDiv(lat,5)*5 + "_" + floorDiv(lon,5)*5 (same scheme as calibration.json). */
    public static String regionId(double lat, double lon) {
        return "B_" + (int) Math.floor(lat / 5) * 5 + "_" + (int) Math.floor(lon / 5) * 5;
    }

    static String key(String regionId, int month) {
        return regionId + "|" + month;
    }
}
