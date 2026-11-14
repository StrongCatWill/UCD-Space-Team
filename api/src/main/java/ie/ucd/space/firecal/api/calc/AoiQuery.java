package ie.ucd.space.firecal.api.calc;

import ie.ucd.space.firecal.api.geo.ResolvedAoi;
import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.YearRange;
import java.time.YearMonth;

/**
 * A validated, defaults-applied AOI request.
 * @param currentYm  the current UTC month (in_progress)
 * @param baselineDefault true when the baseline equals the configured default (demo files are precomputed with it)
 * @param defaults   true when start/end were omitted, baseline is the default and include_static is false:
 *                   only then may precomputed demo summaries answer the request as-is
 */
public record AoiQuery(
        ResolvedAoi aoi,
        YearMonth start,
        YearMonth end,
        YearRange baseline,
        boolean includeStatic,
        ConfidenceClass minConf,
        YearMonth currentYm,
        boolean baselineDefault,
        boolean defaults) {

    /** Demo file may serve calendar cells (filtered to start..end) when baseline/static are default. */
    public boolean demoCalendarUsable() {
        return aoi.demoAreaId() != null && !includeStatic && baselineDefault;
    }
}
