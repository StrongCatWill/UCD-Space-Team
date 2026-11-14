package ie.ucd.space.firecal.api.outlook;

import ie.ucd.space.firecal.contract.api.OutlookLevel;
import java.time.Instant;
import org.locationtech.jts.geom.Geometry;

/** One SPC outlook polygon with level > NONE. */
public record SpcFeature(String product, OutlookLevel level, String label, Instant issuedAt, Instant validTo, Geometry geometry) {}
