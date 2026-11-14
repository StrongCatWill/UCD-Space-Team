package ie.ucd.space.firecal.api.calc;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.contract.api.AnomalyMonth;
import ie.ucd.space.firecal.contract.api.MonthStatus;
import ie.ucd.space.firecal.contract.api.PeakMonth;
import ie.ucd.space.firecal.contract.api.PeakYear;
import ie.ucd.space.firecal.contract.api.SeasonalityPoint;
import ie.ucd.space.firecal.contract.api.SummaryCards;
import ie.ucd.space.firecal.contract.api.SummaryData;
import ie.ucd.space.firecal.contract.api.ThisMonthCard;
import ie.ucd.space.firecal.contract.api.YearTotal;
import ie.ucd.space.firecal.contract.files.DemoAreaFile;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * POST /api/summary data over [start, end]:
 * cards (total, this month = latest month with data, peak complete year, peak month), seasonality per calendar month
 * (baseline median/p25/p75/mean + the end year's value), yearly totals (complete = 12 ok months), top 10 anomalies.
 */
@Service
public class SummaryService {

    static final int TOP_ANOMALIES = 10;

    public SummaryData summary(AoiQuery q, DataStore store) {
        if (q.defaults() && q.aoi().demoAreaId() != null) {
            Optional<DemoAreaFile> demo = store.demoFile(q.aoi().demoAreaId());
            if (demo.isPresent() && demo.get().summary() != null) return demo.get().summary();
        }
        return summary(q, CalendarService.series(q, store));
    }

    public SummaryData summary(AoiQuery q, AoiSeries s) {
        List<AoiSeries.MonthPoint> pts = s.range(q.start(), q.end());
        List<AoiSeries.MonthPoint> withData = pts.stream().filter(p -> p.status() != MonthStatus.no_data).toList();

        double total = withData.stream().mapToDouble(AoiSeries.MonthPoint::mec).sum();

        ThisMonthCard thisMonth = withData.isEmpty()
                ? new ThisMonthCard(q.end(), null, null, null)
                : thisMonth(withData.get(withData.size() - 1), s);

        PeakMonth peakMonth = withData.stream().max(Comparator.comparingDouble(AoiSeries.MonthPoint::mec))
                .map(p -> new PeakMonth(p.ym(), Stats.r1(p.mec()))).orElse(null);

        List<YearTotal> yearly = new ArrayList<>();
        for (int y = q.start().getYear(); y <= q.end().getYear(); y++) {
            final int year = y;
            List<AoiSeries.MonthPoint> ys = pts.stream().filter(p -> p.ym().getYear() == year).toList();
            List<AoiSeries.MonthPoint> yd = ys.stream().filter(p -> p.status() != MonthStatus.no_data).toList();
            if (yd.isEmpty()) continue;
            boolean complete = ys.size() == 12 && ys.stream().allMatch(p -> p.status() == MonthStatus.ok);
            yearly.add(new YearTotal(year, Stats.r1(yd.stream().mapToDouble(AoiSeries.MonthPoint::mec).sum()), complete));
        }
        List<YearTotal> complete = yearly.stream().filter(YearTotal::complete).toList();
        List<YearTotal> peakPool = complete.isEmpty() ? yearly : complete;
        PeakYear peakYear = peakPool.stream().max(Comparator.comparingDouble(YearTotal::mec))
                .map(y -> new PeakYear(y.year(), y.mec())).orElse(null);
        double yearlyMean = complete.stream().mapToDouble(YearTotal::mec).average().orElse(0);

        List<SeasonalityPoint> seasonality = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            Stats.MonthStats st = s.stats(m);
            YearMonth ym = YearMonth.of(q.end().getYear(), m);
            AoiSeries.MonthPoint p = ym.isAfter(q.end()) || ym.isBefore(q.start()) ? null : s.point(ym);
            Double thisYear = p == null || p.status() == MonthStatus.no_data ? null : Stats.r1(p.mec());
            seasonality.add(new SeasonalityPoint(m, Stats.r1(Stats.nz(st.median())), Stats.r1(Stats.nz(st.p25())),
                    Stats.r1(Stats.nz(st.p75())), Stats.r1(Stats.nz(st.mean())), thisYear));
        }

        List<AnomalyMonth> top = withData.stream()
                .filter(p -> p.status() == MonthStatus.ok || p.status() == MonthStatus.provisional)
                .map(p -> {
                    Double median = s.stats(p.ym().getMonthValue()).median();
                    Double an = Stats.anomalyPct(p.mec(), median);
                    return an == null ? null : new AnomalyMonth(p.ym(), Stats.r1(p.mec()), Stats.r1(median), Stats.r1(an));
                })
                .filter(a -> a != null)
                .sorted(Comparator.comparingDouble(AnomalyMonth::anomalyPct).reversed())
                .limit(TOP_ANOMALIES).toList();

        return new SummaryData(new SummaryCards(Stats.r1(total), thisMonth, peakYear, peakMonth),
                seasonality, yearly, Stats.r1(yearlyMean), top);
    }

    private static ThisMonthCard thisMonth(AoiSeries.MonthPoint p, AoiSeries s) {
        Double median = s.stats(p.ym().getMonthValue()).median();
        return new ThisMonthCard(p.ym(), Stats.r1(p.mec()), Stats.r1(median), Stats.r1(Stats.anomalyPct(p.mec(), median)));
    }
}
