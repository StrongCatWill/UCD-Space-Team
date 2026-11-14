package ie.ucd.spaceteam.firecal.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** MODIS 0-29/30-79/80-100 and VIIRS l/n/h mapped to one scale. */
public enum ConfidenceClass {
    @JsonProperty("low") LOW,
    @JsonProperty("nominal") NOMINAL,
    @JsonProperty("high") HIGH
}
