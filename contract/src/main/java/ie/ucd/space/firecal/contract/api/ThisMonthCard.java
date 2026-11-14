package ie.ucd.space.firecal.contract.api;

import java.time.YearMonth;

public record ThisMonthCard(YearMonth ym, Double mec, Double median, Double anomalyPct) {}
