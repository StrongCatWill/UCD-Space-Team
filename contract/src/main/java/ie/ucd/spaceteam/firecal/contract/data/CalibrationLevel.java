package ie.ucd.spaceteam.firecal.contract.data;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Where a calibration factor came from (fallback chain block -> country -> North America). */
public enum CalibrationLevel {
    @JsonProperty("region") REGION,
    @JsonProperty("country") COUNTRY,
    @JsonProperty("north_america") NORTH_AMERICA
}
