package ie.ucd.space.firecal.api.outlook;

import ie.ucd.space.firecal.api.calc.AoiQuery;
import ie.ucd.space.firecal.api.calc.AoiSeries;
import ie.ucd.space.firecal.api.calc.Stats;
import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.contract.api.ExperimentalEstimate;
import ie.ucd.space.firecal.contract.api.ForecastData;
import ie.ucd.space.firecal.contract.api.MonthStatus;
import ie.ucd.space.firecal.contract.api.OfficialOutlook;
import ie.ucd.space.firecal.contract.api.OfficialStatus;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * POST /api/forecast. Two independent parts, never blended:
 * official = SPC outlook polygons intersecting the AOI (US only; Mexico/Canada -> no_feed), highest level first;
 * experimental = baseline median of next month x s, s = clip(sum last 3 complete months / sum of their medians, 0.5, 2).
 */
public class ForecastService {

    public static final String PROVIDER = "NOAA SPC";
    public static final String LINK = "https://www.spc.noaa.gov/products/fire_wx/";
    static final String METHOD = "baseline_median_x_recent_ratio";
    static final String LABEL = "Experimental estimate from FIRMS history (not an official forecast)";

    private final OutlookCache cache;

    public ForecastService(OutlookCache cache) {
        this.cache = cache;
    }

    public ForecastData forecast(AoiQuery q, DataStore store) {
        Official o = official(q);
        return new ForecastData(o.status(), o.outlooks(), experimental(q, store));
    }

    public record Official(OfficialStatus status, List<OfficialOutlook> outlooks) {
        public Optional<OfficialOutlook> highest() {
            return outlooks.stream().findFirst();
        }
    }

    public Official official(AoiQuery q) {
        if (!q.aoi().countries().contains(Country.United_States)) {
            return new Official(OfficialStatus.no_feed, List.of()); // SPC covers CONUS only; CWFIS/SMN are Should
        }
        Optional<OutlookCache.Snapshot> snap = cache.current();
        if (snap.isEmpty()) return new Official(OfficialStatus.upstream_unavailable, List.of());
        Map<String, OfficialOutlook> bestPerProduct = new LinkedHashMap<>();
        for (SpcFeature f : snap.get().features()) {
            if (!f.geometry().intersects(q.aoi().geometry())) continue;
            OfficialOutlook prev = bestPerProduct.get(f.product());
            if (prev == null || f.level().compareTo(prev.level()) > 0) {
                bestPerProduct.put(f.product(), new OfficialOutlook(PROVIDER, f.product(), f.level(), f.label(),
                        f.issuedAt(), f.validTo(), LINK));
            }
        }
        List<OfficialOutlook> list = bestPerProduct.values().stream()
                .sorted(Comparator.comparing(OfficialOutlook::level).reversed()).toList();
        return new Official(list.isEmpty() ? OfficialStatus.none_intersecting : OfficialStatus.available, list);
    }

    public ExperimentalEstimate experimental(AoiQuery q, DataStore store) {
        YearMonth current = q.currentYm();
        AoiSeries s = AoiSeries.compute(store, q.aoi(), current.minusMonths(3), current, q.baseline(), q.includeStatic(), current);
        double sumMec = 0, sumMedian = 0;
        for (int k = 1; k <= 3; k++) {
            AoiSeries.MonthPoint p = s.point(current.minusMonths(k));
            if (p == null || (p.status() != MonthStatus.ok && p.status() != MonthStatus.provisional)) continue;
            Double median = s.stats(p.ym().getMonthValue()).median();
            if (median == null) continue;
            sumMec += p.mec();
            sumMedian += median;
        }
        double scale = sumMedian > 0 ? Math.max(0.5, Math.min(2.0, sumMec / sumMedian)) : 1.0;
        YearMonth target = current.plusMonths(1);
        Stats.MonthStats st = s.stats(target.getMonthValue());
        return new ExperimentalEstimate(target,
                Stats.r1(Stats.nz(st.median()) * scale), Stats.r1(Stats.nz(st.p25()) * scale),
                Stats.r1(Stats.nz(st.p75()) * scale), Math.round(Stats.nz(st.pAny()) * 100) / 100.0,
                Math.round(scale * 100) / 100.0, METHOD, LABEL);
    }
}
