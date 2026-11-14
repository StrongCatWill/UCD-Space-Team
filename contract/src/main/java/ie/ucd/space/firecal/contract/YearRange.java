package ie.ucd.space.firecal.contract;

/** Inclusive range of calendar years, e.g. {"from": 2003, "to": 2025}. */
public record YearRange(int from, int to) {}
