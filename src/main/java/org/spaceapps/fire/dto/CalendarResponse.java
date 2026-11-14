package org.spaceapps.fire.dto;

import java.util.List;

/**
 * Burning-activity calendar for an area.
 * matrix[yearIndex][month-1] = harmonized hotspots (NOAA-20-equivalent); null where there is no data.
 */
public record CalendarResponse(
        List<Double> bbox,
        int cellCount,
        String unit,
        List<Integer> years,
        List<List<Double>> matrix,
        List<Double> monthlyMedian,
        LatestMonth latestCompleteMonth,
        int peakMonth,
        List<YearTotal> topYears,
        String viirsNativeFrom,
        String note) {

    /** ratioToMedian > 1 means busier than the historical median for that calendar month. */
    public record LatestMonth(String month, double value, double historicalMedian, Double ratioToMedian,
                              Double percentileRank) {}

    public record YearTotal(int year, double total) {}
}
