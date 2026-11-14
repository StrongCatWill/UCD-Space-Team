package ie.ucd.space.firecal.contract.api;

import java.time.YearMonth;

public record AnomalyMonth(YearMonth ym, double mec, double median, double anomalyPct) {}
