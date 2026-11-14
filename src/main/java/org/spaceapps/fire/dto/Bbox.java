package org.spaceapps.fire.dto;

import java.util.Arrays;
import java.util.List;

/** Area of interest: west, south, east, north in degrees. */
public record Bbox(double minLon, double minLat, double maxLon, double maxLat) {

    public Bbox {
        if (minLon > maxLon || minLat > maxLat) {
            throw new IllegalArgumentException("bbox must be minLon,minLat,maxLon,maxLat with min <= max");
        }
    }

    /** "minLon,minLat,maxLon,maxLat" (the query-string form used by the API). */
    public static Bbox parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("bbox is required: minLon,minLat,maxLon,maxLat");
        }
        double[] v;
        try {
            v = Arrays.stream(s.split(",")).map(String::trim).mapToDouble(Double::parseDouble).toArray();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bbox values must be numbers: " + s);
        }
        if (v.length != 4) {
            throw new IllegalArgumentException("bbox needs 4 numbers: minLon,minLat,maxLon,maxLat");
        }
        return new Bbox(v[0], v[1], v[2], v[3]);
    }

    public static Bbox of(List<Double> v) {
        if (v == null || v.size() != 4) {
            throw new IllegalArgumentException("bbox needs 4 numbers: [minLon, minLat, maxLon, maxLat]");
        }
        return new Bbox(v.get(0), v.get(1), v.get(2), v.get(3));
    }

    public boolean contains(double lat, double lon) {
        return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
    }

    public List<Double> toList() {
        return List.of(minLon, minLat, maxLon, maxLat);
    }

    public String toQuery() {
        return minLon + "," + minLat + "," + maxLon + "," + maxLat;
    }
}
