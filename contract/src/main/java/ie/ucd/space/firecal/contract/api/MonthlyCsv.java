package ie.ucd.space.firecal.contract.api;

import java.util.List;

/** Column order of POST /api/monthly.csv. */
public final class MonthlyCsv {
    private MonthlyCsv() {}

    public static final List<String> COLUMNS = List.of(
            "year", "month", "mec", "raw_count", "raw_sensor", "median_same_month", "mean_same_month",
            "anomaly_pct", "ci_low", "ci_high", "source_flag", "status", "aoi_area_km2");
}
