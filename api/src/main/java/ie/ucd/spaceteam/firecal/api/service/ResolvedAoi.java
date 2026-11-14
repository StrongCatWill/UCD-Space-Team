package ie.ucd.spaceteam.firecal.api.service;

import org.locationtech.jts.geom.Geometry;

/** An AOI as geometry in lon/lat degrees. demoAreaId is set only for {type: demo}. */
public record ResolvedAoi(String demoAreaId, Geometry geometry, double areaKm2) {

    public boolean isDemo() {
        return demoAreaId != null;
    }
}
