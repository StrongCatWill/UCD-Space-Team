package ie.ucd.space.firecal.contract.files;

import ie.ucd.space.firecal.contract.Country;
import java.util.List;

/** config/demo_areas.json element (the file is a JSON array). bbox = [west, south, east, north] in degrees. */
public record DemoAreaConfig(String areaId, String name, Country country, List<Double> bbox) {}
