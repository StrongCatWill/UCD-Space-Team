package ie.ucd.space.firecal.api.geo;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.web.ApiException;
import ie.ucd.space.firecal.contract.api.Aoi;
import ie.ucd.space.firecal.contract.api.CircleAoi;
import ie.ucd.space.firecal.contract.api.DemoAoi;
import ie.ucd.space.firecal.contract.api.GeoJsonPolygon;
import ie.ucd.space.firecal.contract.api.PolygonAoi;
import ie.ucd.space.firecal.contract.files.DemoAreaConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/**
 * Turns a request "aoi" into a validated {@link ResolvedAoi}. Order of checks: BAD_INPUT, AOI_OUTSIDE_NA, AOI_TOO_LARGE.
 * Circle = geodesic buffer approximated in degrees: dLat = r / 111.32, dLon = r / (111.32 cos lat).
 */
public class AoiResolver {

    static final double EARTH_RADIUS_KM = 6371.0088;
    private static final double KM_PER_DEG = 111.32;
    private static final int CIRCLE_POINTS = 72;
    private static final GeometryFactory GF = new GeometryFactory();

    private final NaBoundary na;
    private final double maxAreaKm2;

    public AoiResolver(NaBoundary na, double maxAreaKm2) {
        this.na = na;
        this.maxAreaKm2 = maxAreaKm2;
    }

    public ResolvedAoi resolve(Aoi aoi, DataStore store) {
        if (aoi == null) throw ApiException.badInput("aoi is required");
        Geometry g;
        double area;
        String demoId = null;
        switch (aoi) {
            case DemoAoi d -> {
                DemoAreaConfig cfg = store.demoArea(d.areaId())
                        .orElseThrow(() -> ApiException.badInput("unknown demo area_id: " + d.areaId()));
                List<Double> b = cfg.bbox();
                g = bbox(b.get(0), b.get(1), b.get(2), b.get(3));
                area = sphericalAreaKm2(g);
                demoId = cfg.areaId();
            }
            case CircleAoi c -> {
                checkLatLon(c.lat(), c.lon());
                if (!(c.radiusKm() > 0) || !Double.isFinite(c.radiusKm())) {
                    throw ApiException.badInput("radius_km must be > 0");
                }
                g = circle(c.lat(), c.lon(), c.radiusKm());
                area = Math.PI * c.radiusKm() * c.radiusKm();
            }
            case PolygonAoi p -> {
                g = polygon(p.geojson());
                area = sphericalAreaKm2(g);
            }
        }
        if (!na.covers(g)) throw ApiException.outsideNa("AOI must lie inside the United States, Canada or Mexico");
        if (area > maxAreaKm2) {
            throw ApiException.tooLarge(String.format(Locale.ROOT, "AOI area %.0f km2 exceeds %.0f km2", area, maxAreaKm2));
        }
        return new ResolvedAoi(g, area, demoId, na.countriesTouching(g));
    }

    static Polygon bbox(double w, double s, double e, double n) {
        return GF.createPolygon(new Coordinate[]{
                new Coordinate(w, s), new Coordinate(e, s), new Coordinate(e, n), new Coordinate(w, n), new Coordinate(w, s)});
    }

    static Polygon circle(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / KM_PER_DEG;
        double dLon = radiusKm / (KM_PER_DEG * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        Coordinate[] ring = new Coordinate[CIRCLE_POINTS + 1];
        for (int i = 0; i < CIRCLE_POINTS; i++) {
            double t = 2 * Math.PI * i / CIRCLE_POINTS;
            ring[i] = new Coordinate(lon + dLon * Math.cos(t), lat + dLat * Math.sin(t));
        }
        ring[CIRCLE_POINTS] = ring[0];
        return GF.createPolygon(ring);
    }

    private static Polygon polygon(GeoJsonPolygon gj) {
        if (gj == null || gj.coordinates() == null) throw ApiException.badInput("aoi.geojson is required");
        if (!"Polygon".equals(gj.type())) throw ApiException.badInput("aoi.geojson.type must be Polygon");
        if (gj.coordinates().isEmpty()) throw ApiException.badInput("aoi.geojson has no rings");
        LinearRing shell = ring(gj.coordinates().get(0));
        LinearRing[] holes = gj.coordinates().subList(1, gj.coordinates().size()).stream()
                .map(AoiResolver::ring).toArray(LinearRing[]::new);
        Polygon p = GF.createPolygon(shell, holes);
        if (!p.isValid()) throw ApiException.badInput("aoi.geojson is not a valid polygon (self-intersecting?)");
        return p;
    }

    private static LinearRing ring(List<List<Double>> pts) {
        if (pts == null) throw ApiException.badInput("aoi.geojson ring is null");
        List<Coordinate> cs = new ArrayList<>();
        for (List<Double> p : pts) {
            if (p == null || p.size() < 2 || p.get(0) == null || p.get(1) == null) {
                throw ApiException.badInput("aoi.geojson positions must be [lon, lat]");
            }
            checkLatLon(p.get(1), p.get(0));
            cs.add(new Coordinate(p.get(0), p.get(1)));
        }
        if (!cs.isEmpty() && !cs.get(0).equals2D(cs.get(cs.size() - 1))) cs.add(new Coordinate(cs.get(0)));
        if (cs.size() < 4) throw ApiException.badInput("aoi.geojson ring needs at least 3 distinct points");
        return GF.createLinearRing(cs.toArray(Coordinate[]::new));
    }

    private static void checkLatLon(double lat, double lon) {
        if (!(lat >= -90 && lat <= 90)) throw ApiException.badInput("lat must be within [-90, 90]");
        if (!(lon >= -180 && lon <= 180)) throw ApiException.badInput("lon must be within [-180, 180]");
    }

    /** Area on the sphere: shell minus holes, R^2/2 * |sum (lon2 - lon1)(2 + sin lat1 + sin lat2)|. */
    static double sphericalAreaKm2(Geometry g) {
        double total = 0;
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Polygon p = (Polygon) g.getGeometryN(i);
            total += ringArea(p.getExteriorRing().getCoordinates());
            for (int h = 0; h < p.getNumInteriorRing(); h++) total -= ringArea(p.getInteriorRingN(h).getCoordinates());
        }
        return total;
    }

    private static double ringArea(Coordinate[] cs) {
        double sum = 0;
        for (int i = 0; i < cs.length - 1; i++) {
            double l1 = Math.toRadians(cs[i].x), l2 = Math.toRadians(cs[i + 1].x);
            double p1 = Math.toRadians(cs[i].y), p2 = Math.toRadians(cs[i + 1].y);
            sum += (l2 - l1) * (2 + Math.sin(p1) + Math.sin(p2));
        }
        return Math.abs(sum) * EARTH_RADIUS_KM * EARTH_RADIUS_KM / 2;
    }
}
