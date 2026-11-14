package ie.ucd.space.firecal.contract.files;

import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.contract.Series;
import ie.ucd.space.firecal.contract.SourceFlag;
import java.time.YearMonth;

/**
 * out/cell_month.json element: harmonized MEC per 0.1 deg cell x month. lat/lon = cell centre.
 * MODIS months: mec == raw_count, raw_sensor = MODIS_AQUA. Months without coverage are absent (missing != zero).
 */
public record CellMonth(
        String cellId,
        double lat,
        double lon,
        Country parent,
        YearMonth ym,
        double mec,
        double mecWithStatic,
        int rawCount,
        Series rawSensor,
        SourceFlag sourceFlag,
        boolean provisional) {}
