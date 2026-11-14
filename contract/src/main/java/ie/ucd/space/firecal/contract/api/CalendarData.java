package ie.ucd.space.firecal.contract.api;

import java.time.YearMonth;
import java.util.List;

/** data of POST /api/calendar. */
public record CalendarData(double aoiAreaKm2, YearMonth switchMonth, List<CalendarCell> cells) {}
