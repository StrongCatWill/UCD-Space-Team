package ie.ucd.spaceteam.firecal.contract.api;

/** e.g. {"name": "NASA FIRMS MODIS C6.1", "level": "SP"}. level = SP | NRT. */
public record DataSourceInfo(String name, String level) {}
