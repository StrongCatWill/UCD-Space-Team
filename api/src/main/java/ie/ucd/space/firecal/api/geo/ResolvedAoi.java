package ie.ucd.space.firecal.api.geo;

import ie.ucd.space.firecal.contract.Country;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

/** A validated AOI: JTS geometry in lon/lat degrees, spherical area, the demo area id (if any) and countries. */
public final class ResolvedAoi {

    private static final GeometryFactory GF = new GeometryFactory();

    private final Geometry geometry;
    private final PreparedGeometry prepared;
    private final Envelope envelope;
    private final double areaKm2;
    private final String demoAreaId;
    private final Set<Country> countries;

    public ResolvedAoi(Geometry geometry, double areaKm2, String demoAreaId, Set<Country> countries) {
        this.geometry = geometry;
        this.prepared = PreparedGeometryFactory.prepare(geometry);
        this.envelope = geometry.getEnvelopeInternal();
        this.areaKm2 = areaKm2;
        this.demoAreaId = demoAreaId;
        this.countries = Set.copyOf(countries);
    }

    /** Point-in-AOI (boundary counts as inside). */
    public boolean contains(double lat, double lon) {
        if (!envelope.contains(lon, lat)) return false;
        return prepared.covers(GF.createPoint(new Coordinate(lon, lat)));
    }

    public Geometry geometry() { return geometry; }
    public double areaKm2() { return areaKm2; }
    public String demoAreaId() { return demoAreaId; }
    public Set<Country> countries() { return countries; }
}
