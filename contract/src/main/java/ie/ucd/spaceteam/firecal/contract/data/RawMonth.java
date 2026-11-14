package ie.ucd.spaceteam.firecal.contract.data;

import ie.ucd.spaceteam.firecal.contract.Country;
import ie.ucd.spaceteam.firecal.contract.Series;
import java.time.YearMonth;

/** out/raw_month.json element: one cell x month x series (unharmonized). */
public record RawMonth(
        String cellId,
        double lat,
        double lon,
        Country parent,
        YearMonth ym,
        Series series,
        int rawCount,
        double frpSum,
        boolean provisional) {}
