package ie.ucd.spaceteam.firecal.contract.data;

import ie.ucd.spaceteam.firecal.contract.Country;

/**
 * a(r,m) = median over train years of Aqua / NOAA-20 (1 km dedup) for region r, calendar month m (1-12).
 * region_id: "B_<floorDiv(lat,5)*5>_<floorDiv(lon,5)*5>" for 5 degree blocks; the country name for country
 * rows; "NA" for north_america rows. parent is null for north_america rows.
 */
public record CalibrationFactor(
        String regionId,
        Country parent,
        int month,
        double a,
        double aP25,
        double aP75,
        int nYears,
        CalibrationLevel level) {}
