package org.spaceapps.fire.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Area summary for one forecast day. expectedFireCells = sum of cell probabilities. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegionDay(
        String date,
        double expectedFireCells,
        double pMean,
        double pClimMean,
        double anomaly,
        Integer observedFireCells) {}
