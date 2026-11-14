package ie.ucd.spaceteam.firecal.contract.api;

import java.util.List;

/** data of POST /api/summary */
public record SummaryData(
        SummaryCards cards,
        List<SeasonalityPoint> seasonality,
        List<YearlyTotal> yearly,
        Double yearlyMean,
        List<MonthAnomaly> topAnomalies) {}
