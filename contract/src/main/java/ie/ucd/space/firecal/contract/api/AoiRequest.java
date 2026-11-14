package ie.ucd.space.firecal.contract.api;

import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.YearRange;
import java.time.YearMonth;

/** Body of every POST. baseline defaults to 2003-2025, min_conf to nominal (applied by the API). */
public record AoiRequest(
        Aoi aoi,
        YearMonth start,
        YearMonth end,
        YearRange baseline,
        boolean includeStatic,
        ConfidenceClass minConf) {}
