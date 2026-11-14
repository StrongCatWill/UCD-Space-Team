package ie.ucd.spaceteam.firecal.contract.data;

import ie.ucd.spaceteam.firecal.contract.ConfidenceClass;
import ie.ucd.spaceteam.firecal.contract.Country;
import ie.ucd.spaceteam.firecal.contract.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One row of out/hotspots/{year}.csv (CSV, not JSON). Column order is {@link #COLUMNS}.
 * hotspot_id = "M|V-YYYYMMDD-<6-digit seq>"; sensor = MODIS | VIIRS; satellite = Aqua | Terra | N20;
 * acq_time_utc = HH:MM; type = FIRMS type (0-3), null for NRT rows.
 */
public record HotspotRow(
        String hotspotId,
        String sensor,
        String satellite,
        double latitude,
        double longitude,
        LocalDate acqDate,
        LocalTime acqTimeUtc,
        String daynight,
        ConfidenceClass confidenceClass,
        Double frpMw,
        Integer type,
        DataSource dataSource,
        Country parent) {

    public static final List<String> COLUMNS = List.of(
            "hotspot_id", "sensor", "satellite", "latitude", "longitude", "acq_date", "acq_time_utc",
            "daynight", "confidence_class", "frp_mw", "type", "data_source", "parent");
}
