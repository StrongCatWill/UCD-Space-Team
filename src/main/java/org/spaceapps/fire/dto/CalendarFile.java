package org.spaceapps.fire.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Shape of calendar.json written by notebook step 13 (one monthly series per 0.5-degree cell). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CalendarFile(
        double grid,
        String unit,
        List<String> months,          // "2000-11" ... "2026-11"
        String viirsNativeFrom,       // "2012-02": earlier months are MODIS x k
        String lastMonthPartialUntil, // e.g. "2026-11-07"
        List<Cell> cells) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cell(String cellId, double lat, double lon, double[] h) {}
}
