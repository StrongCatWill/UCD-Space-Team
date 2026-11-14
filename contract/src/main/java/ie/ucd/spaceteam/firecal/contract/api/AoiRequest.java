package ie.ucd.spaceteam.firecal.contract.api;

import ie.ucd.spaceteam.firecal.contract.ConfidenceClass;
import ie.ucd.spaceteam.firecal.contract.data.YearRange;
import java.time.YearMonth;

/** Body of every POST. baseline / include_static / min_conf are optional (null = server default). */
public record AoiRequest(
        Aoi aoi,
        YearMonth start,
        YearMonth end,
        YearRange baseline,
        Boolean includeStatic,
        ConfidenceClass minConf) {}
