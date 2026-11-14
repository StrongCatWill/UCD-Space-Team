package ie.ucd.space.firecal.contract.api;

import java.util.List;

/** data of POST /api/summary. */
public record SummaryData(
        SummaryCards cards,
        List<SeasonalityPoint> seasonality,
        List<YearTotal> yearly,
        double yearlyMean,
        List<AnomalyMonth> topAnomalies) {}
