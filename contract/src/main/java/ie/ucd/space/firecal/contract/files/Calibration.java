package ie.ucd.space.firecal.contract.files;

import ie.ucd.space.firecal.contract.YearRange;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/** out/calibration.json. */
public record Calibration(
        String harmonizationVersion,
        Instant generatedAt,
        YearRange train,
        YearMonth switchMonth,
        YearMonth seriesStart,
        List<CalibrationFactor> regions,
        List<CalibrationFactor> country,
        List<CalibrationFactor> northAmerica,
        CalibrationValidation validation) {}
