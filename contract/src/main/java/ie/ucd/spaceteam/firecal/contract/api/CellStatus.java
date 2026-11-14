package ie.ucd.spaceteam.firecal.contract.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum CellStatus {
    @JsonProperty("ok") OK,
    @JsonProperty("provisional") PROVISIONAL,
    @JsonProperty("in_progress") IN_PROGRESS,
    @JsonProperty("no_data") NO_DATA
}
