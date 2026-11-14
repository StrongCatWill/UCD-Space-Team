package ie.ucd.space.firecal.contract.api;

import ie.ucd.space.firecal.contract.Series;
import ie.ucd.space.firecal.contract.SourceFlag;
import java.time.YearMonth;

/** One month of the calendar. Nullable: everything except ym/status when no_data; anomaly when median = 0; ci when MODIS. */
public record CalendarCell(
        YearMonth ym,
        Double mec,
        Integer rawCount,
        Series rawSensor,
        Double median,
        Double mean,
        Double anomalyPct,
        Double anomalyPctMean,
        Double ciLow,
        Double ciHigh,
        SourceFlag sourceFlag,
        MonthStatus status) {}
