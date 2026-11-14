package ie.ucd.space.firecal.pipeline.ingest;

import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.pipeline.download.FirmsProduct;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Streaming reader for FIRMS CSVs (country yearly files and Area API output). Columns are read by header name;
 * required headers are asserted per file. Parsing rules follow firms_monthly.py.
 */
public final class FirmsCsv {

    public static final List<String> REQUIRED = List.of(
            "latitude", "longitude", "acq_date", "acq_time", "satellite", "confidence", "version", "frp", "daynight");

    private FirmsCsv() {}

    /** Streams every row of {@code file} to {@code sink}. Returns the number of rows read. */
    public static long read(Path file, FirmsProduct product, Country parent, Consumer<Detection> sink)
            throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(r, file.getFileName().toString(), product, parent, sink);
        }
    }

    public static long read(Reader in, String name, FirmsProduct product, Country parent, Consumer<Detection> sink)
            throws IOException {
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).get();
        try (CSVParser p = CSVParser.parse(in, fmt)) {
            Map<String, Integer> h = p.getHeaderMap();
            List<String> missing = new ArrayList<>();
            for (String c : REQUIRED) if (!h.containsKey(c)) missing.add(c);
            if (!missing.isEmpty()) {
                throw new IllegalStateException(name + ": missing required columns " + missing + "; header = " + h.keySet());
            }
            int iLat = h.get("latitude"), iLon = h.get("longitude"), iDate = h.get("acq_date"),
                    iTime = h.get("acq_time"), iSat = h.get("satellite"), iConf = h.get("confidence"),
                    iVer = h.get("version"), iFrp = h.get("frp"), iDn = h.get("daynight");
            Integer iType = h.get("type");
            long n = 0;
            for (CSVRecord rec : p) {
                n++;
                try {
                    String date = rec.get(iDate); // YYYY-MM-DD
                    String dn = rec.get(iDn);
                    sink.accept(new Detection(
                            Satellite.of(product, rec.get(iSat)),
                            parent,
                            Double.parseDouble(rec.get(iLat)),
                            Double.parseDouble(rec.get(iLon)),
                            Integer.parseInt(date, 0, 4, 10),
                            Integer.parseInt(date, 5, 7, 10),
                            Integer.parseInt(date, 8, 10, 10),
                            Integer.parseInt(rec.get(iTime)),
                            confidence(rec.get(iConf)),
                            Double.parseDouble(rec.get(iFrp)),
                            iType == null ? -1 : Integer.parseInt(rec.get(iType)),
                            dn.isEmpty() ? '?' : dn.charAt(0),
                            rec.get(iVer).toUpperCase(Locale.ROOT).contains("RT")));
                } catch (RuntimeException e) {
                    throw new IllegalStateException(name + " line " + (rec.getRecordNumber() + 1) + ": " + e.getMessage(), e);
                }
            }
            return n;
        }
    }

    /** MODIS 0-29 low, 30-79 nominal, 80-100 high; VIIRS l/n/h (or low/nominal/high). */
    public static ConfidenceClass confidence(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty confidence");
        char c = s.charAt(0);
        if (Character.isDigit(c)) {
            int v = Integer.parseInt(s);
            if (v < 0 || v > 100) throw new IllegalArgumentException("MODIS confidence out of range: " + v);
            return v < 30 ? ConfidenceClass.low : v < 80 ? ConfidenceClass.nominal : ConfidenceClass.high;
        }
        return switch (Character.toLowerCase(c)) {
            case 'l' -> ConfidenceClass.low;
            case 'n' -> ConfidenceClass.nominal;
            case 'h' -> ConfidenceClass.high;
            default -> throw new IllegalArgumentException("unknown confidence: " + raw);
        };
    }
}
