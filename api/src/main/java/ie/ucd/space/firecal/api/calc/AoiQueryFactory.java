package ie.ucd.space.firecal.api.calc;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.geo.AoiResolver;
import ie.ucd.space.firecal.api.geo.ResolvedAoi;
import ie.ucd.space.firecal.api.web.ApiException;
import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.YearRange;
import ie.ucd.space.firecal.contract.api.AoiRequest;
import java.time.Clock;
import java.time.YearMonth;

/** Validates an {@link AoiRequest} and applies defaults (start = series_start, end = current month, baseline, nominal). */
public class AoiQueryFactory {

    private static final int MIN_YEAR = 2000;
    private final AoiResolver resolver;
    private final Clock clock;
    private final YearRange defaultBaseline;

    public AoiQueryFactory(AoiResolver resolver, Clock clock, YearRange defaultBaseline) {
        this.resolver = resolver;
        this.clock = clock;
        this.defaultBaseline = defaultBaseline;
    }

    public YearRange defaultBaseline() {
        return defaultBaseline;
    }

    public YearMonth currentYm() {
        return YearMonth.now(clock);
    }

    public AoiQuery from(AoiRequest req, DataStore store) {
        if (req == null) throw ApiException.badInput("request body is required");
        YearMonth now = currentYm();
        YearMonth start = req.start() != null ? req.start() : store.calibration().seriesStart();
        YearMonth end = req.end() != null ? req.end() : now;
        if (start.isAfter(end)) throw ApiException.badInput("start must be <= end");
        if (start.getYear() < MIN_YEAR) throw ApiException.badInput("start must be " + MIN_YEAR + "-01 or later");
        if (end.isAfter(now.plusYears(1))) throw ApiException.badInput("end is too far in the future");

        YearRange baseline = req.baseline() != null ? req.baseline() : defaultBaseline;
        if (baseline.from() > baseline.to()) throw ApiException.badInput("baseline.from must be <= baseline.to");
        if (baseline.from() < MIN_YEAR || baseline.to() > now.getYear()) {
            throw ApiException.badInput("baseline years must be within " + MIN_YEAR + ".." + now.getYear());
        }
        ConfidenceClass minConf = req.minConf() != null ? req.minConf() : ConfidenceClass.nominal;

        ResolvedAoi aoi = resolver.resolve(req.aoi(), store);
        boolean baselineDefault = baseline.equals(defaultBaseline);
        boolean defaults = req.start() == null && req.end() == null && baselineDefault && !req.includeStatic();
        return new AoiQuery(aoi, start, end, baseline, req.includeStatic(), minConf, now, baselineDefault, defaults);
    }
}
