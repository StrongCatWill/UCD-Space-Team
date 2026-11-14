package ie.ucd.space.firecal.contract.api;

import java.time.YearMonth;

/** Our own estimate; never blended with official outlooks. */
public record ExperimentalEstimate(
        YearMonth targetMonth,
        double expectedMec,
        double p25,
        double p75,
        double pAny,
        double scaleS,
        String method,
        String label) {}
