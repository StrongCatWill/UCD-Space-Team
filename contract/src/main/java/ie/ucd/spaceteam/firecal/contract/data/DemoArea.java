package ie.ucd.spaceteam.firecal.contract.data;

import ie.ucd.spaceteam.firecal.contract.Country;
import java.util.List;

/** config/demo_areas.json element. bbox = [west, south, east, north] in degrees. */
public record DemoArea(String areaId, String name, Country country, List<Double> bbox) {}
