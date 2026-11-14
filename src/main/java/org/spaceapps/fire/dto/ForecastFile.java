package org.spaceapps.fire.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Shape of forecasts.json written by notebook step 25. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForecastFile(String modelVersion, String asOf, List<CellForecast> items) {}
