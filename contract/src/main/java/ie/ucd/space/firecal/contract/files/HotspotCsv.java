package ie.ucd.space.firecal.contract.files;

import java.util.List;

/**
 * out/hotspots/{year}.csv. Rows sorted by acq_date, acq_time_utc.
 * hotspot_id = "M|V-YYYYMMDD-<6-digit seq>"; sensor = Series name; acq_time_utc = "HH:MM";
 * confidence_class = ConfidenceClass; type = FIRMS type 0-3 (empty for NRT); data_source = SP|NRT; parent = Country.
 * The API export appends one column: harmonization_version.
 */
public final class HotspotCsv {
    private HotspotCsv() {}

    public static final List<String> COLUMNS = List.of(
            "hotspot_id", "sensor", "satellite", "latitude", "longitude", "acq_date", "acq_time_utc",
            "daynight", "confidence_class", "frp_mw", "type", "data_source", "parent");
}
