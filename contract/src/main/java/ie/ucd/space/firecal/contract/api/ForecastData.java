package ie.ucd.space.firecal.contract.api;

import java.util.List;

/** data of POST /api/forecast. */
public record ForecastData(
        OfficialStatus officialStatus,
        List<OfficialOutlook> official,
        ExperimentalEstimate experimental) {}
