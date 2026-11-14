package ie.ucd.space.firecal.contract.files;

import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.contract.Series;
import java.time.YearMonth;

/** out/raw_month.json element: un-harmonized counts per cell x month x series. */
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
