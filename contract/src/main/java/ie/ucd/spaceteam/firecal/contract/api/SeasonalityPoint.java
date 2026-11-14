package ie.ucd.spaceteam.firecal.contract.api;

/** month = calendar month 1-12; baseline stats over that month; this_year null if not yet observed. */
public record SeasonalityPoint(int month, Double median, Double p25, Double p75, Double mean, Double thisYear) {}
