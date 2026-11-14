package ie.ucd.space.firecal.api.calc;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.geo.ResolvedAoi;
import ie.ucd.space.firecal.contract.Series;
import ie.ucd.space.firecal.contract.SourceFlag;
import ie.ucd.space.firecal.contract.YearRange;
import ie.ucd.space.firecal.contract.api.CalendarCell;
import ie.ucd.space.firecal.contract.api.MonthStatus;
import ie.ucd.space.firecal.contract.files.CalibrationFactor;
import ie.ucd.space.firecal.contract.files.CellMonth;
import ie.ucd.space.firecal.contract.files.SeriesCoverage;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monthly MEC summed over the cells whose centre lies in the AOI, plus per-calendar-month baseline statistics.
 *
 * Status per month: before series_start / before series coverage / after the last data month -> no_data
 * (never zero); the current month -> in_progress; NRT rows or past the series' sp_until -> provisional; else ok.
 * A month inside coverage without any cell rows is a real zero.
 */
public final class AoiSeries {

    /** One AOI month; numeric fields null when status = no_data. ci only for VIIRS_CAL months. */
    public record MonthPoint(YearMonth ym, MonthStatus status, Double mec, Integer rawCount, Series rawSensor,
                             SourceFlag sourceFlag, Double ciLow, Double ciHigh) {}

    private final Map<YearMonth, MonthPoint> points;
    private final Stats.MonthStats[] baseline = new Stats.MonthStats[13];
    private final int cellCount;

    private AoiSeries(Map<YearMonth, MonthPoint> points, YearRange baselineYears, int cellCount) {
        this.points = points;
        this.cellCount = cellCount;
        for (int m = 1; m <= 12; m++) {
            List<Double> values = new ArrayList<>();
            for (int y = baselineYears.from(); y <= baselineYears.to(); y++) {
                MonthPoint p = points.get(YearMonth.of(y, m));
                if (p != null && p.status() == MonthStatus.ok) values.add(p.mec());
            }
            baseline[m] = Stats.of(m, values);
        }
    }

    public static AoiSeries compute(DataStore store, ResolvedAoi aoi, YearMonth from, YearMonth to,
                                    YearRange baselineYears, boolean includeStatic, YearMonth currentYm) {
        YearMonth lo = min(from, YearMonth.of(baselineYears.from(), 1));
        YearMonth hi = max(to, YearMonth.of(baselineYears.to(), 12));
        YearMonth switchMonth = store.calibration().switchMonth();

        Map<YearMonth, double[]> acc = new HashMap<>(); // mec, raw, ciLow, ciHigh, provisional
        int cells = 0;
        for (DataStore.CellRef c : store.cells()) {
            if (!aoi.contains(c.lat(), c.lon())) continue;
            cells++;
            for (CellMonth cm : store.monthsOf(c.cellId())) {
                if (cm.ym().isBefore(lo) || cm.ym().isAfter(hi)) continue;
                double v = includeStatic ? cm.mecWithStatic() : cm.mec();
                double[] a = acc.computeIfAbsent(cm.ym(), k -> new double[5]);
                a[0] += v;
                a[1] += cm.rawCount();
                if (!cm.ym().isBefore(switchMonth)) {
                    Optional<CalibrationFactor> f = store.factorFor(c.lat(), c.lon(), c.parent(), cm.ym().getMonthValue());
                    boolean ok = f.isPresent() && f.get().a() > 0;
                    a[2] += ok ? v * f.get().aP25() / f.get().a() : v;
                    a[3] += ok ? v * f.get().aP75() / f.get().a() : v;
                }
                if (cm.provisional()) a[4] = 1;
            }
        }

        Map<YearMonth, MonthPoint> points = new LinkedHashMap<>();
        for (YearMonth ym = lo; !ym.isAfter(hi); ym = ym.plusMonths(1)) {
            boolean viirs = !ym.isBefore(switchMonth);
            Series sensor = viirs ? Series.VIIRS_NOAA20 : Series.MODIS_AQUA;
            SourceFlag flag = viirs ? SourceFlag.VIIRS_CAL : SourceFlag.MODIS;
            SeriesCoverage cov = viirs ? store.coverage().viirsNoaa20() : store.coverage().modisAqua();
            double[] a = acc.get(ym);
            MonthStatus status = status(ym, a != null && a[4] > 0, cov, store, currentYm);
            if (status == MonthStatus.no_data) {
                points.put(ym, new MonthPoint(ym, status, null, null, null, null, null, null));
            } else {
                double mec = a == null ? 0 : a[0];
                int raw = a == null ? 0 : (int) a[1];
                Double ciLow = viirs ? (a == null ? 0.0 : a[2]) : null;
                Double ciHigh = viirs ? (a == null ? 0.0 : a[3]) : null;
                points.put(ym, new MonthPoint(ym, status, mec, raw, sensor, flag, ciLow, ciHigh));
            }
        }
        return new AoiSeries(points, baselineYears, cells);
    }

    private static MonthStatus status(YearMonth ym, boolean anyProvisional, SeriesCoverage cov, DataStore store, YearMonth currentYm) {
        if (ym.isBefore(store.calibration().seriesStart())) return MonthStatus.no_data;
        if (store.maxYm() == null || ym.isAfter(store.maxYm()) || ym.isAfter(currentYm)) return MonthStatus.no_data;
        if (cov == null || cov.from() == null || ym.atEndOfMonth().isBefore(cov.from())) return MonthStatus.no_data;
        if (ym.equals(currentYm)) return MonthStatus.in_progress;
        if (anyProvisional || cov.spUntil() == null || ym.atEndOfMonth().isAfter(cov.spUntil())) return MonthStatus.provisional;
        return MonthStatus.ok;
    }

    public MonthPoint point(YearMonth ym) {
        return points.get(ym);
    }

    public Stats.MonthStats stats(int month) {
        return baseline[month];
    }

    public int cellCount() {
        return cellCount;
    }

    /** Points in [from, to], oldest first. */
    public List<MonthPoint> range(YearMonth from, YearMonth to) {
        List<MonthPoint> out = new ArrayList<>();
        for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) out.add(points.get(ym));
        return out;
    }

    /** API calendar cell, numbers rounded to 1 decimal. */
    public CalendarCell cell(YearMonth ym) {
        MonthPoint p = points.get(ym);
        if (p.status() == MonthStatus.no_data) {
            return new CalendarCell(ym, null, null, null, null, null, null, null, null, null, null, MonthStatus.no_data);
        }
        Stats.MonthStats s = baseline[ym.getMonthValue()];
        return new CalendarCell(ym, Stats.r1(p.mec()), p.rawCount(), p.rawSensor(), Stats.r1(s.median()), Stats.r1(s.mean()),
                Stats.r1(Stats.anomalyPct(p.mec(), s.median())), Stats.r1(Stats.anomalyPct(p.mec(), s.mean())),
                Stats.r1(p.ciLow()), Stats.r1(p.ciHigh()), p.sourceFlag(), p.status());
    }

    private static YearMonth min(YearMonth a, YearMonth b) { return a.isBefore(b) ? a : b; }
    private static YearMonth max(YearMonth a, YearMonth b) { return a.isAfter(b) ? a : b; }
}
