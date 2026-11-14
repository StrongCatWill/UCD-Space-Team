package ie.ucd.space.firecal.api.outlook;

import java.util.List;

/** Fetches all current outlook polygons (throws on any upstream problem). */
@FunctionalInterface
public interface OutlookSource {
    List<SpcFeature> fetchAll() throws Exception;
}
