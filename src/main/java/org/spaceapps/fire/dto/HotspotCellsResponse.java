package org.spaceapps.fire.dto;

import java.util.List;

/** Map layer: one coloured 0.5-degree square per cell with hotspots in [from, to]. */
public record HotspotCellsResponse(
        List<Double> bbox,
        String from,
        String to,
        String unit,
        double cellSize,
        double maxHotspots,             // for the colour ramp
        List<Cell> cells,
        String note) {

    /** bounds = [west, south, east, north] of the square; hotspots = harmonized total over the range. */
    public record Cell(String cellId, double lat, double lon, List<Double> bounds, double hotspots, int monthsWithFire) {}
}
