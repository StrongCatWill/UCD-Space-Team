package ie.ucd.space.firecal.api.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ie.ucd.space.firecal.contract.Country;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.operation.union.UnaryUnionOp;

/**
 * US / Canada / Mexico outlines (Natural Earth 1:110m admin-0, public domain; classpath geo/na_countries.geojson).
 * 1:110m is coarse, so both checks use a tolerance buffer (default 1 deg) that also admits coastal AOIs.
 */
public final class NaBoundary {

    /** share of the AOI (planar degrees) that must fall inside the tolerant NA union */
    static final double MIN_INSIDE_FRACTION = 0.5;

    private final Geometry tolerantUnionGeom;
    private final PreparedGeometry tolerantUnion;
    private final Map<Country, PreparedGeometry> tolerantCountries;

    private NaBoundary(Map<Country, Geometry> countries, double toleranceDeg) {
        this.tolerantCountries = new EnumMap<>(Country.class);
        countries.forEach((c, g) -> tolerantCountries.put(c, PreparedGeometryFactory.prepare(g.buffer(toleranceDeg / 2))));
        Geometry union = UnaryUnionOp.union(countries.values());
        this.tolerantUnionGeom = union.buffer(toleranceDeg);
        this.tolerantUnion = PreparedGeometryFactory.prepare(tolerantUnionGeom);
    }

    public static NaBoundary fromClasspath(double toleranceDeg) {
        try (InputStream in = NaBoundary.class.getResourceAsStream("/geo/na_countries.geojson")) {
            if (in == null) throw new IllegalStateException("geo/na_countries.geojson missing from classpath");
            JsonNode root = new ObjectMapper().readTree(in);
            GeoJsonReader reader = new GeoJsonReader();
            Map<Country, Geometry> countries = new EnumMap<>(Country.class);
            for (JsonNode f : root.get("features")) {
                Country c = Country.valueOf(f.get("properties").get("country").asText());
                countries.put(c, reader.read(f.get("geometry").toString()));
            }
            if (countries.size() != 3) throw new IllegalStateException("expected 3 countries, got " + countries.keySet());
            return new NaBoundary(countries, toleranceDeg);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ParseException e) {
            throw new IllegalStateException("geo/na_countries.geojson: " + e.getMessage(), e);
        }
    }

    /**
     * AOI is "inside" US + Canada + Mexico when at least half of it lies within the land outline buffered by the
     * tolerance. A strict covers() would reject coastal boxes such as the norcal demo bbox (reaches 1.5 deg into the
     * Pacific); cells over the ocean simply do not exist in the data.
     */
    public boolean covers(Geometry aoi) {
        if (tolerantUnion.covers(aoi)) return true;
        if (!tolerantUnion.intersects(aoi)) return false;
        return tolerantUnionGeom.intersection(aoi).getArea() >= MIN_INSIDE_FRACTION * aoi.getArea();
    }

    /** Countries the AOI touches (with half the tolerance), e.g. to decide which outlook feeds apply. */
    public Set<Country> countriesTouching(Geometry aoi) {
        Set<Country> out = EnumSet.noneOf(Country.class);
        tolerantCountries.forEach((c, g) -> {
            if (g.intersects(aoi)) out.add(c);
        });
        return out;
    }
}
