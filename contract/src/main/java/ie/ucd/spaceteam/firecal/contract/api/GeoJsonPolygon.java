package ie.ucd.spaceteam.firecal.contract.api;

import java.util.List;

/** GeoJSON Polygon geometry: type = "Polygon", coordinates = rings of [lon, lat]. */
public record GeoJsonPolygon(String type, List<List<List<Double>>> coordinates) {}
