package ie.ucd.space.firecal.api.calc;

import java.util.Arrays;
import java.util.List;

/** Small numeric helpers. Percentiles use linear interpolation (numpy default). */
public final class Stats {
    private Stats() {}

    /** Per calendar month over the baseline years (complete, non-provisional months only). */
    public record MonthStats(int month, int n, Double median, Double mean, Double p25, Double p75, Double pAny) {
        public static MonthStats empty(int month) {
            return new MonthStats(month, 0, null, null, null, null, null);
        }
    }

    public static MonthStats of(int month, List<Double> values) {
        if (values.isEmpty()) return MonthStats.empty(month);
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double mean = Arrays.stream(v).average().orElse(0);
        double pAny = Arrays.stream(v).filter(x -> x > 0).count() / (double) v.length;
        return new MonthStats(month, v.length, percentile(v, 0.5), mean, percentile(v, 0.25), percentile(v, 0.75), pAny);
    }

    static double percentile(double[] sorted, double p) {
        double pos = p * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    /** (value - ref) / ref * 100; null when either is missing or ref is 0. */
    public static Double anomalyPct(Double value, Double ref) {
        if (value == null || ref == null || ref == 0) return null;
        return (value - ref) / ref * 100;
    }

    /** API numbers have 1 decimal. */
    public static Double r1(Double x) {
        return x == null ? null : Math.round(x * 10) / 10.0;
    }

    public static double r1(double x) {
        return Math.round(x * 10) / 10.0;
    }

    public static double nz(Double x) {
        return x == null ? 0 : x;
    }
}
