package ie.ucd.spaceteam.firecal.contract.data;

import ie.ucd.spaceteam.firecal.contract.Country;
import ie.ucd.spaceteam.firecal.contract.Series;
import ie.ucd.spaceteam.firecal.contract.SourceFlag;
import java.time.YearMonth;

/** out/cell_month.json element: one 0.1 degree cell x one month (harmonized series). lat/lon = cell centre. */
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
