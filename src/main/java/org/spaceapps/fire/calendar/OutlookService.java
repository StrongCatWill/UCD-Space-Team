package org.spaceapps.fire.calendar;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.spaceapps.fire.dto.Bbox;
import org.spaceapps.fire.dto.OutlookResponse;
import org.spaceapps.fire.dto.OutlookResponse.Insights;
import org.spaceapps.fire.dto.OutlookResponse.ModelConfidence;
import org.spaceapps.fire.dto.OutlookResponse.Point;
import org.spaceapps.fire.dto.OutlookResponse.Risk;

/**
 * Long-term monthly statistics for an area: the observed monthly record plus, for the next 1-12 months,
 * the empirical distribution of the same calendar month over all complete past months (median, 80% and 95%
 * ranges). This is climatology from the harmonized calendar, not a model inference; the 7-day LSTM
 * forecast stays in the Python service behind /api/forecast.
 */
public class OutlookService {

    private static final int MIN_YEARS_FOR_CHECK = 5;

    private final CalendarService calendar;

    public OutlookService(CalendarService calendar) {
        this.calendar = calendar;
    }

    public OutlookResponse outlook(Bbox box, int months) {
        if (months < 1 || months > 12) throw new IllegalArgumentException("months must be 1..12");
        int[] count = new int[1];
        double[] s = calendar.series(box, count);
        YearMonth first = calendar.firstMonth(), lastC = calendar.lastCompleteMonth();

        double[][] byMonth = new double[13][];
        double[] medianByMonth = new double[13];
        double maxMedian = 0;
        for (int m = 1; m <= 12; m++) {
            byMonth[m] = sorted(calendar.sameMonthValues(s, m, null));
            medianByMonth[m] = quantile(byMonth[m], 0.5);
            maxMedian = Math.max(maxMedian, medianByMonth[m]);
        }

        List<Point> points = new ArrayList<>();
        for (YearMonth ym = first; !ym.isAfter(lastC); ym = ym.plusMonths(1)) {
            points.add(new Point(ym.toString(), "historical", r1(s[idx(first, ym)]), null, null, null, null, null));
        }
        List<Risk> risks = new ArrayList<>();
        YearMonth peak = null;
        for (int k = 1; k <= months; k++) {
            YearMonth ym = lastC.plusMonths(k);
            double[] v = byMonth[ym.getMonthValue()];
            double med = quantile(v, 0.5);
            points.add(new Point(ym.toString(), "forecast", null, r1(med), r1(quantile(v, 0.10)), r1(quantile(v, 0.90)),
                    r1(quantile(v, 0.025)), r1(quantile(v, 0.975))));
            int score = maxMedian > 0 ? (int) Math.round(med / maxMedian * 100) : 0;
            risks.add(new Risk(ym.toString(), level(score), score));
            if (peak == null || med > medianByMonth[peak.getMonthValue()]) peak = ym;
        }

        Insights insights = new Insights(nextMonthsChange(s, first, lastC, medianByMonth, Math.min(3, months)),
                highRiskPeriod(risks, peak), longTermTrend(s, first, lastC));
        int years = completeYears(first, lastC).size();
        ModelConfidence confidence = new ModelConfidence(intervalHitRate(s, first, lastC), "80% range hit rate, leave-one-year-out (80 = well calibrated)",
                List.of(years + " complete years of harmonized monthly counts",
                        "Peak calendar month: " + monthName(argmax(medianByMonth)) + " (median " + r1(maxMedian) + ")",
                        "Method: same-month historical distribution (climatology), not the 7-day LSTM"));

        return new OutlookResponse("harmonized", calendar.unit(), points, risks, label(peak), insights, confidence, false,
                "climatology", box.toList(), count[0], calendar.coverage(box),
                "Forecast months = distribution of that calendar month in past complete months for this area. "
                        + "For day-by-day detection probability (next 1-7 days) use POST /api/forecast.");
    }

    /** % change of the next k forecast medians vs the same k months one year earlier. */
    private static Double nextMonthsChange(double[] s, YearMonth first, YearMonth lastC, double[] medianByMonth, int k) {
        double fc = 0, prev = 0;
        for (int i = 1; i <= k; i++) {
            YearMonth ym = lastC.plusMonths(i);
            fc += medianByMonth[ym.getMonthValue()];
            int j = idx(first, ym.minusYears(1));
            if (j < 0) return null;
            prev += s[j];
        }
        return prev > 0 ? r1((fc - prev) / prev * 100) : null;
    }

    /** % change of the mean annual total, last 5 complete years vs first 5 complete years. */
    private static Double longTermTrend(double[] s, YearMonth first, YearMonth lastC) {
        List<Integer> ys = completeYears(first, lastC);
        if (ys.size() < 10) return null;
        double a = 0, b = 0;
        for (int i = 0; i < 5; i++) {
            a += annual(s, first, ys.get(i));
            b += annual(s, first, ys.get(ys.size() - 1 - i));
        }
        return a > 0 ? r1((b - a) / a * 100) : null;
    }

    /** Share of complete past months whose value fell inside the 10-90% range of the other years. */
    private Integer intervalHitRate(double[] s, YearMonth first, YearMonth lastC) {
        int hits = 0, n = 0;
        for (YearMonth ym = first; !ym.isAfter(lastC); ym = ym.plusMonths(1)) {
            double[] others = sorted(calendar.sameMonthValues(s, ym.getMonthValue(), ym));
            if (others.length < MIN_YEARS_FOR_CHECK) continue;
            double v = s[idx(first, ym)];
            n++;
            if (v >= quantile(others, 0.10) && v <= quantile(others, 0.90)) hits++;
        }
        return n == 0 ? null : (int) Math.round(100.0 * hits / n);
    }

    private static String highRiskPeriod(List<Risk> risks, YearMonth peak) {
        int p = -1;
        for (int i = 0; i < risks.size(); i++) if (risks.get(i).month().equals(peak.toString())) p = i;
        if (!isHigh(risks.get(p))) return monthName(peak.getMonthValue());
        int lo = p, hi = p;
        while (lo > 0 && isHigh(risks.get(lo - 1))) lo--;
        while (hi < risks.size() - 1 && isHigh(risks.get(hi + 1))) hi++;
        String a = monthName(YearMonth.parse(risks.get(lo).month()).getMonthValue());
        String b = monthName(YearMonth.parse(risks.get(hi).month()).getMonthValue());
        return lo == hi ? a : a + " to " + b;
    }

    private static boolean isHigh(Risk r) {
        return r.level().equals("high") || r.level().equals("extreme");
    }

    static String level(int score) {
        if (score >= 85) return "extreme";
        if (score >= 60) return "high";
        if (score >= 30) return "moderate";
        return "low";
    }

    private static List<Integer> completeYears(YearMonth first, YearMonth lastC) {
        List<Integer> ys = new ArrayList<>();
        for (int y = first.getYear(); y <= lastC.getYear(); y++) {
            if (!YearMonth.of(y, 1).isBefore(first) && !YearMonth.of(y, 12).isAfter(lastC)) ys.add(y);
        }
        return ys;
    }

    private static double annual(double[] s, YearMonth first, int y) {
        double t = 0;
        for (int m = 1; m <= 12; m++) t += s[idx(first, YearMonth.of(y, m))];
        return t;
    }

    /** Linear-interpolated quantile of sorted values (0 for an empty array). */
    static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    private static double[] sorted(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c;
    }

    private static int argmax(double[] byMonth) {
        int best = 1;
        for (int m = 2; m <= 12; m++) if (byMonth[m] > byMonth[best]) best = m;
        return best;
    }

    private static int idx(YearMonth first, YearMonth ym) {
        long i = first.until(ym, java.time.temporal.ChronoUnit.MONTHS);
        return i < 0 ? -1 : (int) i;
    }

    private static String monthName(int m) {
        return java.time.Month.of(m).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String label(YearMonth ym) {
        return monthName(ym.getMonthValue()) + " " + ym.getYear();
    }

    private static Double r1(double v) {
        return CalendarService.round1(v);
    }
}
