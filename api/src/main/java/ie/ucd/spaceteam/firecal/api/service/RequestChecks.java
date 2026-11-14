package ie.ucd.spaceteam.firecal.api.service;

import ie.ucd.spaceteam.firecal.api.web.ApiException;
import ie.ucd.spaceteam.firecal.contract.ConfidenceClass;
import ie.ucd.spaceteam.firecal.contract.api.AoiRequest;
import ie.ucd.spaceteam.firecal.contract.data.YearRange;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** Period / baseline checks shared by all POST endpoints. Bad "YYYY-MM" strings already fail in JSON parsing. */
public final class RequestChecks {

    public static final YearRange DEFAULT_BASELINE = new YearRange(2003, 2025);
    static final YearMonth EARLIEST = YearMonth.of(2000, 1);
    static final long MAX_MONTHS = 50 * 12;

    private RequestChecks() {}

    public static void period(AoiRequest req) {
        if (req.start() == null || req.end() == null) {
            throw ApiException.badInput("start and end (YYYY-MM) are required");
        }
        if (req.start().isAfter(req.end())) {
            throw ApiException.badInput("start must be <= end");
        }
        if (req.start().isBefore(EARLIEST) || ChronoUnit.MONTHS.between(req.start(), req.end()) >= MAX_MONTHS) {
            throw ApiException.badInput("period must start in 2000 or later and span at most 50 years");
        }
        YearRange b = baseline(req);
        if (b.from() > b.to()) {
            throw ApiException.badInput("baseline.from must be <= baseline.to");
        }
        if (b.from() < DEFAULT_BASELINE.from() || b.to() > DEFAULT_BASELINE.to()) {
            throw ApiException.badInput("baseline must lie within 2003..2025");
        }
    }

    public static YearRange baseline(AoiRequest req) {
        return req.baseline() == null ? DEFAULT_BASELINE : req.baseline();
    }

    public static boolean includeStatic(AoiRequest req) {
        return Boolean.TRUE.equals(req.includeStatic());
    }

    public static ConfidenceClass minConf(AoiRequest req) {
        return req.minConf() == null ? ConfidenceClass.NOMINAL : req.minConf();
    }
}
