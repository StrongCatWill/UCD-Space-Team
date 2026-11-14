package org.spaceapps.fire.dto;

import java.util.List;

/** Historical monthly activity for an area; records match the frontend MonthlyActivity type. */
public record ActivityResponse(
        List<Double> bbox,
        int cellCount,
        String unit,
        String viirsNativeFrom,
        String lastCompleteMonth,
        List<Double> monthlyMean,      // index month-1, complete months only
        List<Double> monthlyMedian,
        List<MonthlyActivity> records,
        String note) {

    public record MonthlyActivity(
            int year,
            int month,
            Double harmonized,          // monthly total, NOAA-20-equivalent hotspots
            Double modis,               // always null: raw MODIS counts are not served
            Double viirs,               // = harmonized where VIIRS-native, else null
            boolean complete,
            Double dailyMean,           // harmonized / days of data in the month
            String source) {}           // "MODISxk" | "VIIRS"
}
