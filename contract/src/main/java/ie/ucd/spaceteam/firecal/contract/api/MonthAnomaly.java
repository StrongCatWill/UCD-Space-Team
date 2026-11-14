package ie.ucd.spaceteam.firecal.contract.api;

import java.time.YearMonth;

/** Used for cards.this_month and top_anomalies[]. */
public record MonthAnomaly(YearMonth ym, Double mec, Double median, Double anomalyPct) {}
