package org.spaceapps.fire.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Same JSON shape as the Python API: {"bbox":[minLon,minLat,maxLon,maxLat], "asOf":"2026-11-07", "days":7}.
 * List (not double[]) so that equals/hashCode work as a cache key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForecastRequest(
        @NotNull @Size(min = 4, max = 4) List<Double> bbox,
        String asOf,
        @Min(1) @Max(7) Integer days) {

    /** Python rejects "days": null, so always send a number. Also validates the bbox. */
    public ForecastRequest withDefaults() {
        Bbox.of(bbox);
        return new ForecastRequest(List.copyOf(bbox), asOf, days == null ? 7 : days);
    }
}
