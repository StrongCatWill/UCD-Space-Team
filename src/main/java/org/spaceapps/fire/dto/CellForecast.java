package org.spaceapps.fire.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One 0.5-degree cell on one day.
 * p        = P(at least one VIIRS hotspot, NOAA-20-equivalent, that local day)  - detection, not ignition
 * pClim    = same probability from 2012-2021 climatology for that day of year
 * observed = soft label (0, 0.5, 1) once the day has been observed, otherwise null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CellForecast(
        String date,
        String cellId,
        double lat,
        double lon,
        double p,
        double pClim,
        double anomaly,
        Double observed) {}
