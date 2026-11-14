package ie.ucd.space.firecal.contract.files;

import ie.ucd.space.firecal.contract.Country;

/**
 * a(r,m) = median over train years of (Aqua / NOAA-20 1 km-dedup) for one region and calendar month.
 * region_id: "B_{floorDiv(lat,5)*5}_{floorDiv(lon,5)*5}" for blocks, the country name for country level,
 * "NA" for north_america. parent is null at north_america level. level = level the value was finally taken from.
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
