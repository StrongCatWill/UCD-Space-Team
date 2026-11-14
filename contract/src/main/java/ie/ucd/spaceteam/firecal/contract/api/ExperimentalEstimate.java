package ie.ucd.spaceteam.firecal.contract.api;

import java.time.YearMonth;

/** Our own next-month estimate: baseline median x scale_s, scale_s clipped to [0.5, 2]. */
public record ExperimentalEstimate(
        YearMonth targetMonth,
        Double expectedMec,
        Double p25,
        Double p75,
        Double pAny,
        Double scaleS,
        String method,
        String label) {}
