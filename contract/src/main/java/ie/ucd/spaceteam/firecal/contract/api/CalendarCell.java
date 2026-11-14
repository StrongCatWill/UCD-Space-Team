package ie.ucd.spaceteam.firecal.contract.api;

import ie.ucd.spaceteam.firecal.contract.Series;
import ie.ucd.spaceteam.firecal.contract.SourceFlag;
import java.time.YearMonth;

/** One month in the calendar. For status no_data every number is null (missing is not zero). */
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
        CellStatus status) {}
