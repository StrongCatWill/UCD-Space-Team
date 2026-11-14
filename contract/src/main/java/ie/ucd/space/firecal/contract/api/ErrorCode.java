package ie.ucd.space.firecal.contract.api;

/** BAD_INPUT, AOI_OUTSIDE_NA, AOI_TOO_LARGE -> 400; UPSTREAM_UNAVAILABLE -> 503. */
public enum ErrorCode { BAD_INPUT, AOI_OUTSIDE_NA, AOI_TOO_LARGE, UPSTREAM_UNAVAILABLE }
