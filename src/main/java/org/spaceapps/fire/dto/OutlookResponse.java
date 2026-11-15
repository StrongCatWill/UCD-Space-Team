package org.spaceapps.fire.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Long-term monthly outlook for an area. Field names match the frontend PredictionDashboardData type.
 * Forecast months come from the area's same-month historical distribution (climatology), not from the
 * 7-day LSTM; "method" says so explicitly.
 */
public record OutlookResponse(
        String sensorMode,                 // always "harmonized"
        String unit,
        List<Point> points,
        List<Risk> risks,
        String highRiskMonth,              // e.g. "August 2027"
        Insights insights,
        ModelConfidence modelConfidence,
        @JsonProperty("isMock") boolean isMock,
        String method,
        List<Double> bbox,
        int cellCount,
        String coverage,
        String note) {

    public record Point(String month, String phase, Double observed, Double forecastMedian,
                        Double lower80, Double upper80, Double lower95, Double upper95) {}

    /** score 0-100 = forecast median relative to the area's busiest calendar month. */
    public record Risk(String month, String level, int score) {}

    public record Insights(Double nextThreeMonthsChangePercent, String highRiskPeriod, Double longTermTrendPercent) {}

    public record ModelConfidence(Integer scorePercent, String metric, List<String> factors) {}
}
