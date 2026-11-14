package ie.ucd.space.firecal.contract.api;

import java.util.List;

/** GeoJSON Polygon geometry: type = "Polygon", coordinates = rings of [lon, lat]; first ring = outer. */
public record GeoJsonPolygon(String type, List<List<List<Double>>> coordinates) {}
