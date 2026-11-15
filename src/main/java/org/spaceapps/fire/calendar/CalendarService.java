package org.spaceapps.fire.calendar;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.spaceapps.fire.dto.ActivityResponse;
import org.spaceapps.fire.dto.Bbox;
import org.spaceapps.fire.dto.HotspotCellsResponse;
import org.spaceapps.fire.dto.CalendarFile;
import org.spaceapps.fire.dto.CalendarResponse;
import org.spaceapps.fire.dto.CalendarResponse.LatestMonth;
import org.spaceapps.fire.dto.CalendarResponse.YearTotal;
import tools.jackson.databind.json.JsonMapper;

/**
 * Harmonized burning-activity calendar (MODIS x k before 2012-02, VIIRS after), computed for any bbox
 * by summing the 0.5-degree cell series from calendar.json. Pure Java, no Python needed.
 */
public class CalendarService {

    private final CalendarFile file;
    private final YearMonth first;
    private final YearMonth lastComplete;   // last month with a full month of data

    public CalendarService(CalendarFile file) {
        this.file = file;
        this.first = YearMonth.parse(file.months().getFirst());
        YearMonth last = YearMonth.parse(file.months().getLast());
        LocalDate until = LocalDate.parse(file.lastMonthPartialUntil());
        boolean lastIsPartial = YearMonth.from(until).equals(last) && until.getDayOfMonth() < last.lengthOfMonth();
        this.lastComplete = lastIsPartial ? last.minusMonths(1) : last;
    }

    public static CalendarService load(org.springframework.core.io.Resource resource, JsonMapper mapper) {
        try (InputStream in = resource.getInputStream()) {
            return new CalendarService(mapper.readValue(in, CalendarFile.class));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read calendar " + resource, e);
        }
    }

    /** Monthly totals for the bbox, aligned with file.months(). */
    double[] series(Bbox box, int[] cellCount) {
        double[] total = new double[file.months().size()];
        int n = 0;
        for (CalendarFile.Cell c : file.cells()) {
            if (!box.contains(c.lat(), c.lon())) continue;
            n++;
            for (int i = 0; i < total.length; i++) total[i] += c.h()[i];
        }
        cellCount[0] = n;
        return total;
    }

    public CalendarResponse calendar(Bbox box) {
        int[] count = new int[1];
        double[] s = series(box, count);
        YearMonth lastMonth = YearMonth.parse(file.months().getLast());
        int y0 = first.getYear(), y1 = lastMonth.getYear();

        List<Integer> years = new ArrayList<>();
        List<List<Double>> matrix = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            years.add(y);
            List<Double> row = new ArrayList<>(12);
            for (int m = 1; m <= 12; m++) {
                int i = index(YearMonth.of(y, m));
                row.add(i < 0 ? null : round1(s[i]));
            }
            matrix.add(row);
        }

        // historical median per calendar month over complete months only
        List<Double> monthlyMedian = new ArrayList<>(12);
        List<Double> monthlyMean = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            double[] vals = valuesFor(s, m, null);
            monthlyMedian.add(round1(median(vals)));
            monthlyMean.add(round1(mean(vals)));
        }

        int iLast = index(lastComplete);
        double v = s[iLast];
        double[] hist = valuesFor(s, lastComplete.getMonthValue(), lastComplete);   // same month, other years
        double med = median(hist);
        long below = Arrays.stream(hist).filter(x -> x < v).count();
        LatestMonth latest = new LatestMonth(lastComplete.toString(), round1(v), round1(med),
                med > 0 ? Math.round(v / med * 100) / 100.0 : null,
                hist.length == 0 ? null : Math.round(100.0 * below / hist.length * 10) / 10.0);

        int peak = 1;
        for (int m = 2; m <= 12; m++) if (monthlyMedian.get(m - 1) > monthlyMedian.get(peak - 1)) peak = m;

        List<YearTotal> totals = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            if (YearMonth.of(y, 1).isBefore(first) || YearMonth.of(y, 12).isAfter(lastComplete)) continue; // full years only
            double t = 0;
            for (int m = 1; m <= 12; m++) t += s[index(YearMonth.of(y, m))];
            totals.add(new YearTotal(y, round1(t)));
        }
        totals.sort(Comparator.comparingDouble(YearTotal::total).reversed());

        return new CalendarResponse(box.toList(), count[0], coverage(box), file.unit(), years, matrix, monthlyMedian, monthlyMean,
                latest, peak,
                totals.subList(0, Math.min(3, totals.size())), file.viirsNativeFrom(),
                "Months before " + file.viirsNativeFrom() + " are MODIS scaled to NOAA-20-equivalent counts; "
                        + "data runs to " + file.lastMonthPartialUntil() + ".");
    }

    /** CSV download: one row per month for the bbox. */
    public String csv(Bbox box) {
        int[] count = new int[1];
        double[] s = series(box, count);
        YearMonth viirs = YearMonth.parse(file.viirsNativeFrom());
        StringBuilder sb = new StringBuilder("month,hotspots_noaa20_equivalent,source,cells\n");
        for (int i = 0; i < s.length; i++) {
            YearMonth ym = YearMonth.parse(file.months().get(i));
            sb.append(ym).append(',').append(round1(s[i])).append(',')
              .append(ym.isBefore(viirs) ? "MODISxk" : "VIIRS")
              .append(ym.isAfter(lastComplete) ? "-partial" : "")
              .append(',').append(count[0]).append('\n');
        }
        return sb.toString();
    }

    /**
     * CSV download of the hotspots behind a search: one row per 0.5-degree cell and month with a non-zero
     * harmonized count, for cells inside the bbox and months in [from, to] (YYYY-MM, both optional).
     */
    public String hotspotsCsv(Bbox box, String from, String to) {
        int[] r = monthRange(from, to);
        int i0 = r[0], i1 = r[1];
        YearMonth viirs = YearMonth.parse(file.viirsNativeFrom());
        StringBuilder sb = new StringBuilder("month,cellId,lat,lon,hotspots_noaa20_equivalent,source\n");
        for (int i = i0; i <= i1; i++) {
            YearMonth ym = first.plusMonths(i);
            String source = (ym.isBefore(viirs) ? "MODISxk" : "VIIRS") + (ym.isAfter(lastComplete) ? "-partial" : "");
            for (CalendarFile.Cell c : file.cells()) {
                if (c.h()[i] <= 0 || !box.contains(c.lat(), c.lon())) continue;
                sb.append(ym).append(',').append(c.cellId()).append(',').append(c.lat()).append(',').append(c.lon())
                  .append(',').append(round1(c.h()[i])).append(',').append(source).append('\n');
            }
        }
        return sb.toString();
    }

    /** Map layer for the frontend: cells with hotspots in [from, to] as coloured 0.5-degree squares. */
    public HotspotCellsResponse hotspotCells(Bbox box, String from, String to) {
        int[] r = monthRange(from, to);
        double half = file.grid() / 2;
        List<HotspotCellsResponse.Cell> out = new ArrayList<>();
        double max = 0;
        for (CalendarFile.Cell c : file.cells()) {
            if (!box.contains(c.lat(), c.lon())) continue;
            double total = 0;
            int months = 0;
            for (int i = r[0]; i <= r[1]; i++) {
                total += c.h()[i];
                if (c.h()[i] > 0) months++;
            }
            if (total <= 0) continue;
            max = Math.max(max, total);
            out.add(new HotspotCellsResponse.Cell(c.cellId(), c.lat(), c.lon(),
                    List.of(c.lon() - half, c.lat() - half, c.lon() + half, c.lat() + half), round1(total), months));
        }
        return new HotspotCellsResponse(box.toList(), coverage(box), first.plusMonths(r[0]).toString(), first.plusMonths(r[1]).toString(),
                file.unit().replace("per month", "in range"), file.grid(), round1(max), out,
                "Harmonized counts per 0.5-degree cell; draw each as a square. Point view: GET /api/hotspots/points.");
    }

    /** [i0, i1] month indexes for optional YYYY-MM bounds (defaults: whole record). */
    private int[] monthRange(String from, String to) {
        YearMonth lo = from == null || from.isBlank() ? first : parseMonth(from, "from");
        YearMonth hi = to == null || to.isBlank() ? first.plusMonths(file.months().size() - 1L) : parseMonth(to, "to");
        if (lo.isAfter(hi)) throw new IllegalArgumentException("from must not be after to");
        int i0 = Math.max(0, (int) first.until(lo, java.time.temporal.ChronoUnit.MONTHS));
        int i1 = Math.min(file.months().size() - 1, (int) first.until(hi, java.time.temporal.ChronoUnit.MONTHS));
        if (i0 > i1) throw new IllegalArgumentException("range is outside the record " + first + ".." + file.months().getLast());
        return new int[] {i0, i1};
    }

    private static YearMonth parseMonth(String s, String name) {
        try {
            return YearMonth.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be YYYY-MM, got: " + s);
        }
    }

    /**
     * Historical monthly activity for the bbox, one record per month of the record.
     * harmonized = monthly total; dailyMean = total / days of data in that month (fair for the partial last month).
     * viirs = same value where the month is VIIRS-native; modis = null (raw MODIS counts are not in calendar.json).
     */
    public ActivityResponse activity(Bbox box) {
        int[] count = new int[1];
        double[] s = series(box, count);
        YearMonth viirs = YearMonth.parse(file.viirsNativeFrom());
        LocalDate until = LocalDate.parse(file.lastMonthPartialUntil());
        List<ActivityResponse.MonthlyActivity> records = new ArrayList<>(s.length);
        for (int i = 0; i < s.length; i++) {
            YearMonth ym = first.plusMonths(i);
            boolean complete = !ym.isAfter(lastComplete);
            int days = complete ? ym.lengthOfMonth() : Math.max(1, until.getDayOfMonth());
            double v = round1(s[i]);
            boolean native_ = !ym.isBefore(viirs);
            Double med = null, anomaly = null, pct = null;
            if (complete) {
                double[] others = valuesFor(s, ym.getMonthValue(), ym);   // leave this year out
                if (others.length > 0) {
                    double m = median(others);
                    med = round1(m);
                    anomaly = round1(s[i] - m);
                    pct = m > 0 ? round1((s[i] - m) / m * 100) : null;
                }
            }
            records.add(new ActivityResponse.MonthlyActivity(ym.getYear(), ym.getMonthValue(), v, null,
                    native_ ? v : null, complete, Math.round(s[i] / days * 100) / 100.0, native_ ? "VIIRS" : "MODISxk",
                    med, anomaly, pct));
        }
        List<Double> monthlyMean = new ArrayList<>(12), monthlyMedian = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            double[] vals = valuesFor(s, m, null);
            monthlyMean.add(round1(mean(vals)));
            monthlyMedian.add(round1(median(vals)));
        }
        return new ActivityResponse(box.toList(), count[0], coverage(box), file.unit(), file.viirsNativeFrom(), lastComplete.toString(),
                monthlyMean, monthlyMedian, records,
                "monthlyMean/monthlyMedian use complete months only. Months before " + file.viirsNativeFrom()
                        + " are MODIS scaled to NOAA-20-equivalent counts.");
    }

    /**
     * "inside" if any data cell lies within 1 degree of the ROI, else "outside" (the prototype covers the United
     * States). Inside the area a cell with no fire in 25 years is genuinely 0, so cellCount 0 alone is not enough.
     */
    public String coverage(Bbox box) {
        Bbox near = new Bbox(box.minLon() - 1, box.minLat() - 1, box.maxLon() + 1, box.maxLat() + 1);
        for (CalendarFile.Cell c : file.cells()) if (near.contains(c.lat(), c.lon())) return "inside";
        return "outside";
    }

    // ---- accessors for OutlookService (same package) ----
    YearMonth firstMonth() { return first; }
    YearMonth lastCompleteMonth() { return lastComplete; }
    String unit() { return file.unit(); }

    /** Values of calendar month m over complete months (optionally excluding one month). */
    double[] sameMonthValues(double[] s, int month, YearMonth exclude) { return valuesFor(s, month, exclude); }

    static double mean(double[] v) {
        return v.length == 0 ? 0 : Arrays.stream(v).sum() / v.length;
    }

    private int index(YearMonth ym) {
        long i = first.until(ym, java.time.temporal.ChronoUnit.MONTHS);
        return (i < 0 || i >= file.months().size()) ? -1 : (int) i;
    }

    private double[] valuesFor(double[] s, int month, YearMonth exclude) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < s.length; i++) {
            YearMonth ym = first.plusMonths(i);
            if (ym.getMonthValue() != month || ym.isAfter(lastComplete) || ym.equals(exclude)) continue;
            out.add(s[i]);
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static double median(double[] v) {
        if (v.length == 0) return 0;
        double[] c = v.clone();
        Arrays.sort(c);
        return c.length % 2 == 1 ? c[c.length / 2] : (c[c.length / 2 - 1] + c[c.length / 2]) / 2;
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
