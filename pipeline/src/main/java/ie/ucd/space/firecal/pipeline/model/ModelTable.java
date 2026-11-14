package ie.ucd.space.firecal.pipeline.model;

import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.pipeline.ingest.Detection;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dense cell x year x month table for models/ (schema: models/README.md "Input schema").
 *
 * <ul>
 *   <li>0.5 deg cells, {@code cell_id = "%.2f_%.2f"} of the cell centre (e.g. {@code 38.25_-121.75}).</li>
 *   <li>One row per cell and month from {@code fromYear}-01 to {@code toYear}-12, zero months included,
 *       for every cell with at least one kept detection in any series.</li>
 *   <li>VIIRS columns are empty (null = no coverage) before the series starts, and a number (0 = observed,
 *       no fire) from then on. MODIS covers every month of the table.</li>
 *   <li>Kept detections: confidence &ge; {@code minConf}, type 0 (vegetation) only, as firms_monthly.py.</li>
 * </ul>
 */
public final class ModelTable {

    public static final double CELL_DEG = 0.5;
    public static final String LAND_COVER_PLACEHOLDER = "unknown";
    public static final List<String> COLUMNS = List.of(
            "cell_id", "lat", "lon", "region", "country", "land_cover", "year", "month",
            "modis_count", "modis_count_terra", "modis_count_aqua", "modis_count_high_conf",
            "modis_frp_sum", "modis_frp_max",
            "viirs_snpp_count", "viirs_snpp_frp_sum", "viirs_noaa20_count", "viirs_noaa20_frp_sum");

    /** First year with data per VIIRS series (null before it). */
    public static final int SNPP_FROM = 2012;
    public static final int NOAA20_FROM = 2018;

    private static final class Acc {
        int terra, aqua, modisHigh, snpp, n20;
        double modisFrpSum, modisFrpMax, snppFrp, n20Frp;
    }

    public record Stats(long seen, long kept, long droppedConfidence, long droppedType, long droppedYear) {}

    private final int fromYear, toYear, nMonths;
    private final ConfidenceClass minConf;
    private final Map<Long, Acc> acc = new HashMap<>();
    /** cell key -> detections per Country (for the cell's majority country). */
    private final Map<Integer, long[]> countryVotes = new HashMap<>();
    private long seen, kept, droppedConf, droppedType, droppedYear;

    public ModelTable(int fromYear, int toYear, ConfidenceClass minConf) {
        if (fromYear > toYear) throw new IllegalArgumentException("fromYear > toYear");
        this.fromYear = fromYear;
        this.toYear = toYear;
        this.nMonths = (toYear - fromYear + 1) * 12;
        this.minConf = minConf;
    }

    static int latIdx(double lat) { return (int) Math.floor(lat / CELL_DEG); }
    static int lonIdx(double lon) { return (int) Math.floor(lon / CELL_DEG); }
    /** Packs (latIdx, lonIdx) into one int; lat idx in [-180,180], lon idx in [-360,360]. */
    static int cellKey(int latIdx, int lonIdx) { return (latIdx + 200) * 1000 + (lonIdx + 400); }
    static double centreLat(int cellKey) { return (cellKey / 1000 - 200 + 0.5) * CELL_DEG; }
    static double centreLon(int cellKey) { return (cellKey % 1000 - 400 + 0.5) * CELL_DEG; }

    public static String cellId(double centreLat, double centreLon) {
        return String.format(Locale.ROOT, "%.2f_%.2f", centreLat, centreLon);
    }

    /** 5 deg block of the cell centre, same id scheme as calibration.json region_id. */
    public static String regionBlock(double lat, double lon) {
        return "B_" + Math.floorDiv((int) Math.floor(lat), 5) * 5 + "_" + Math.floorDiv((int) Math.floor(lon), 5) * 5;
    }

    public void accept(Detection d) {
        seen++;
        if (d.year() < fromYear || d.year() > toYear) { droppedYear++; return; }
        if (d.confidence().compareTo(minConf) < 0) { droppedConf++; return; }
        if (d.type() != 0 && d.type() != -1) { droppedType++; return; }
        kept++;
        int ck = cellKey(latIdx(d.lat()), lonIdx(d.lon()));
        int mi = (d.year() - fromYear) * 12 + d.month() - 1;
        Acc a = acc.computeIfAbsent((long) ck * 1000 + mi, k -> new Acc());
        switch (d.satellite()) {
            case TERRA -> { a.terra++; modis(a, d); }
            case AQUA -> { a.aqua++; modis(a, d); }
            case SNPP -> { a.snpp++; a.snppFrp += d.frp(); }
            case NOAA20 -> { a.n20++; a.n20Frp += d.frp(); }
        }
        countryVotes.computeIfAbsent(ck, k -> new long[Country.values().length])[d.parent().ordinal()]++;
    }

    private static void modis(Acc a, Detection d) {
        if (d.confidence() == ConfidenceClass.high) a.modisHigh++;
        a.modisFrpSum += d.frp();
        a.modisFrpMax = Math.max(a.modisFrpMax, d.frp());
    }

    public Stats stats() {
        return new Stats(seen, kept, droppedConf, droppedType, droppedYear);
    }

    public int cellCount() {
        return countryVotes.size();
    }

    /** Writes the dense CSV, sorted by cell_id, year, month. Returns the number of data rows. */
    public long write(Writer out) throws IOException {
        BufferedWriter w = out instanceof BufferedWriter b ? b : new BufferedWriter(out, 1 << 16);
        w.write(String.join(",", COLUMNS));
        w.write('\n');
        record Cell(int key, String id) {}
        List<Cell> cells = new ArrayList<>(countryVotes.size());
        for (int key : countryVotes.keySet()) cells.add(new Cell(key, cellId(centreLat(key), centreLon(key))));
        cells.sort((x, y) -> x.id().compareTo(y.id()));

        long rows = 0;
        StringBuilder sb = new StringBuilder(160);
        for (Cell c : cells) {
            double lat = centreLat(c.key()), lon = centreLon(c.key());
            String prefix = c.id() + "," + num(lat, 2) + "," + num(lon, 2) + "," + regionBlock(lat, lon) + ","
                    + majority(countryVotes.get(c.key())).name() + "," + LAND_COVER_PLACEHOLDER + ",";
            for (int mi = 0; mi < nMonths; mi++) {
                int year = fromYear + mi / 12, month = mi % 12 + 1;
                Acc a = acc.get((long) c.key() * 1000 + mi);
                sb.setLength(0);
                sb.append(prefix).append(year).append(',').append(month).append(',');
                if (a == null) {
                    sb.append("0,0,0,0,0.0,0.0,");
                } else {
                    sb.append(a.terra + a.aqua).append(',').append(a.terra).append(',').append(a.aqua).append(',')
                            .append(a.modisHigh).append(',');
                    dec1(sb, a.modisFrpSum).append(',');
                    dec1(sb, a.modisFrpMax).append(',');
                }
                viirs(sb, year >= SNPP_FROM, a == null ? 0 : a.snpp, a == null ? 0 : a.snppFrp).append(',');
                viirs(sb, year >= NOAA20_FROM, a == null ? 0 : a.n20, a == null ? 0 : a.n20Frp).append('\n');
                w.append(sb);
                rows++;
            }
        }
        w.flush();
        return rows;
    }

    private static StringBuilder viirs(StringBuilder sb, boolean covered, int count, double frp) {
        if (!covered) return sb.append(',');            // null, null
        return dec1(sb.append(count).append(','), frp);
    }

    /** Country with most kept detections in the cell; ties go to the first in enum order. */
    static Country majority(long[] votes) {
        int best = 0;
        for (int i = 1; i < votes.length; i++) if (votes[i] > votes[best]) best = i;
        return Country.values()[best];
    }

    /** Appends a non-negative value rounded to 1 decimal (fast path for millions of rows). */
    static StringBuilder dec1(StringBuilder sb, double v) {
        if (v < 0) throw new IllegalArgumentException("negative value: " + v);
        long r = Math.round(v * 10);
        return sb.append(r / 10).append('.').append(r % 10);
    }

    static String num(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }
}
