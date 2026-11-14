package ie.ucd.spaceteam.firecal.contract.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Area of interest, discriminated by "type": demo | polygon | circle. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DemoAoi.class, name = "demo"),
        @JsonSubTypes.Type(value = PolygonAoi.class, name = "polygon"),
        @JsonSubTypes.Type(value = CircleAoi.class, name = "circle")
})
public sealed interface Aoi permits DemoAoi, PolygonAoi, CircleAoi {}
