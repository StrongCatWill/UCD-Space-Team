package ie.ucd.spaceteam.firecal.contract.data;

import com.fasterxml.jackson.annotation.JsonProperty;

/** out/coverage.json */
public record Coverage(
        @JsonProperty("MODIS_AQUA") SeriesCoverage modisAqua,
        @JsonProperty("VIIRS_NOAA20") SeriesCoverage viirsNoaa20) {}
