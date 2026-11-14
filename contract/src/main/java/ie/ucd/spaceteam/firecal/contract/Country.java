package ie.ucd.spaceteam.firecal.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** "parent" of a cell; spelled as in the FIRMS country file names. */
public enum Country {
    @JsonProperty("United_States") UNITED_STATES,
    @JsonProperty("Canada") CANADA,
    @JsonProperty("Mexico") MEXICO
}
