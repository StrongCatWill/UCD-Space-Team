package ie.ucd.space.firecal.contract.files;

import com.fasterxml.jackson.annotation.JsonProperty;

/** out/coverage.json: {"MODIS_AQUA": {...}, "VIIRS_NOAA20": {...}}. Keys are upper-case by contract. */
public record Coverage(
        @JsonProperty("MODIS_AQUA") SeriesCoverage modisAqua,
        @JsonProperty("VIIRS_NOAA20") SeriesCoverage viirsNoaa20) {}
