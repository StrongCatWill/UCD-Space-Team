package ie.ucd.spaceteam.firecal.api.service;

import ie.ucd.spaceteam.firecal.api.store.DataStore;
import ie.ucd.spaceteam.firecal.api.web.ApiException;
import ie.ucd.spaceteam.firecal.contract.api.Aoi;
import ie.ucd.spaceteam.firecal.contract.api.CircleAoi;
import ie.ucd.spaceteam.firecal.contract.api.DemoAoi;
import ie.ucd.spaceteam.firecal.contract.api.ErrorCode;
import ie.ucd.spaceteam.firecal.contract.api.GeoJsonPolygon;
import ie.ucd.spaceteam.firecal.contract.api.PolygonAoi;
import ie.ucd.spaceteam.firecal.contract.data.DemoArea;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Validates an AOI and turns it into a JTS geometry (lon/lat degrees).
 * Area: planar degrees with cos(lat) correction — good enough for the 1,000,000 km2 limit.
 * Outside-NA check is a bounding-box check for now; the Natural Earth country check comes with real aggregation.
 */
@Component
public class AoiResolver {

    static final double MAX_AREA_KM2 = 1_000_000;
    static final Envelope NORTH_AMERICA = new Envelope(-170, -50, 14, 72);
    private static final double KM_PER_DEG_LAT = 110.574;
    private static final double KM_PER_DEG_LON_EQ = 111.320;
    private static final int CIRCLE_POINTS = 64;

    private final GeometryFactory gf = new GeometryFactory();

    public ResolvedAoi resolve(Aoi aoi, DataStore store) {
        if (aoi == null) {
            throw ApiException.badInput("aoi is required");
        }
        ResolvedAoi r = switch (aoi) {
            case DemoAoi d -> demo(d, store);
            case PolygonAoi p -> new ResolvedAoi(null, polygon(p.geojson()), 0);
            case CircleAoi c -> new ResolvedAoi(null, circle(c), 0);
        };
        Geometry g = r.geometry();
        double area = r.isDemo() ? areaKm2(g) : checkedArea(g);
        if (!NORTH_AMERICA.contains(g.getEnvelopeInternal())) {
            throw new ApiException(ErrorCode.AOI_OUTSIDE_NA, HttpStatus.BAD_REQUEST,
                    "AOI must lie inside the United States, Canada or Mexico");
        }
        return new ResolvedAoi(r.demoAreaId(), g, area);
    }

    private ResolvedAoi demo(DemoAoi d, DataStore store) {
        DemoArea a = store.demoArea(d.areaId())
                .orElseThrow(() -> ApiException.badInput("unknown demo area_id: " + d.areaId()));
        List<Double> b = a.bbox();
        return new ResolvedAoi(a.areaId(), gf.toGeometry(new Envelope(b.get(0), b.get(2), b.get(1), b.get(3))), 0);
    }

    private Geometry polygon(GeoJsonPolygon p) {
        if (p == null || !"Polygon".equals(p.type()) || p.coordinates() == null || p.coordinates().isEmpty()) {
            throw ApiException.badInput("geojson must be a GeoJSON Polygon");
        }
        LinearRing shell = ring(p.coordinates().get(0));
        LinearRing[] holes = p.coordinates().stream().skip(1).map(this::ring).toArray(LinearRing[]::new);
        Geometry g = gf.createPolygon(shell, holes);
        if (!g.isValid()) {
            throw ApiException.badInput("polygon is not valid (self-intersecting?)");
        }
        return g;
    }

    private LinearRing ring(List<List<Double>> pts) {
        if (pts == null || pts.size() < 4) {
            throw ApiException.badInput("polygon ring needs at least 4 positions (closed)");
        }
        Coordinate[] cs = pts.stream().map(this::position).toArray(Coordinate[]::new);
        if (!cs[0].equals2D(cs[cs.length - 1])) {
            throw ApiException.badInput("polygon ring must be closed (first position = last)");
        }
        return gf.createLinearRing(cs);
    }

    private Coordinate position(List<Double> p) {
        if (p == null || p.size() < 2 || p.get(0) == null || p.get(1) == null) {
            throw ApiException.badInput("positions must be [lon, lat]");
        }
        checkLatLon(p.get(1), p.get(0));
        return new Coordinate(p.get(0), p.get(1));
    }

    private Geometry circle(CircleAoi c) {
        checkLatLon(c.lat(), c.lon());
        if (!(c.radiusKm() > 0)) {
            throw ApiException.badInput("radius_km must be > 0");
        }
        double dLat = c.radiusKm() / KM_PER_DEG_LAT;
        double dLon = c.radiusKm() / (KM_PER_DEG_LON_EQ * Math.max(Math.cos(Math.toRadians(c.lat())), 1e-6));
        Coordinate[] cs = new Coordinate[CIRCLE_POINTS + 1];
        for (int i = 0; i < CIRCLE_POINTS; i++) {
            double t = 2 * Math.PI * i / CIRCLE_POINTS;
            cs[i] = new Coordinate(c.lon() + dLon * Math.cos(t), c.lat() + dLat * Math.sin(t));
        }
        cs[CIRCLE_POINTS] = cs[0];
        return gf.createPolygon(cs);
    }

    private static void checkLatLon(double lat, double lon) {
        if (lat < -90 || lat > 90) {
            throw ApiException.badInput("lat must be within -90..90");
        }
        if (lon < -180 || lon > 180) {
            throw ApiException.badInput("lon must be within -180..180");
        }
    }

    private static double checkedArea(Geometry g) {
        double area = areaKm2(g);
        if (area > MAX_AREA_KM2) {
            throw new ApiException(ErrorCode.AOI_TOO_LARGE, HttpStatus.BAD_REQUEST,
                    "AOI is %.0f km2; the limit is 1,000,000 km2".formatted(area));
        }
        return area;
    }

    static double areaKm2(Geometry g) {
        double lat = g.getCentroid().getY();
        return g.getArea() * KM_PER_DEG_LAT * KM_PER_DEG_LON_EQ * Math.cos(Math.toRadians(lat));
    }
}
