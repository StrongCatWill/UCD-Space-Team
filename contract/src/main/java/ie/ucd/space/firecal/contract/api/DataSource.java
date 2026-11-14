package ie.ucd.space.firecal.contract.api;

import ie.ucd.space.firecal.contract.DataLevel;

/** e.g. {"name": "NASA FIRMS MODIS C6.1", "level": "SP"}. */
public record DataSource(String name, DataLevel level) {}
