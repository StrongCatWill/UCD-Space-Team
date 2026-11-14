package org.spaceapps.fire.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** source = "model-api" (live Python service) or "fallback-precomputed" (forecasts.json). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForecastResponse(
        String modelVersion,
        String asOf,
        int cells,
        List<RegionDay> region,
        List<CellForecast> items,
        String source) {

    public ForecastResponse withSource(String newSource) {
        return new ForecastResponse(modelVersion, asOf, cells, region, items, newSource);
    }
}
