package ie.ucd.space.firecal.contract.api;

/** Per calendar month (1-12) over the baseline; this_year null where no data yet. */
public record SeasonalityPoint(int month, double median, double p25, double p75, double mean, Double thisYear) {}
