package ie.ucd.spaceteam.firecal.contract.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum OfficialStatus {
    @JsonProperty("available") AVAILABLE,
    @JsonProperty("none_intersecting") NONE_INTERSECTING,
    @JsonProperty("no_feed") NO_FEED,
    @JsonProperty("upstream_unavailable") UPSTREAM_UNAVAILABLE
}
