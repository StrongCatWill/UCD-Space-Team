package ie.ucd.spaceteam.firecal.contract.api;

import ie.ucd.spaceteam.firecal.contract.data.Coverage;
import ie.ucd.spaceteam.firecal.contract.data.DemoArea;
import java.time.YearMonth;
import java.util.List;

/** data of GET /api/meta */
public record MetaData(
        YearMonth switchMonth,
        YearMonth seriesStart,
        CalibrationMeta calibration,
        Coverage coverage,
        List<DemoArea> demoAreas) {}
