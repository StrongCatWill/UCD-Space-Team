package ie.ucd.space.firecal.contract.api;

import ie.ucd.space.firecal.contract.files.Coverage;
import ie.ucd.space.firecal.contract.files.DemoAreaConfig;
import java.time.YearMonth;
import java.util.List;

/** data of GET /api/meta. demo_areas reuses the config/demo_areas.json shape. */
public record MetaData(
        YearMonth switchMonth,
        YearMonth seriesStart,
        MetaCalibration calibration,
        Coverage coverage,
        List<DemoAreaConfig> demoAreas) {}
