package ie.ucd.spaceteam.firecal.api.service;

import ie.ucd.spaceteam.firecal.api.store.DataStore;
import ie.ucd.spaceteam.firecal.api.store.DataStoreLoader;
import ie.ucd.spaceteam.firecal.api.web.ApiException;
import ie.ucd.spaceteam.firecal.contract.ConfidenceClass;
import ie.ucd.spaceteam.firecal.contract.api.AoiRequest;
import ie.ucd.spaceteam.firecal.contract.api.CalendarCell;
import ie.ucd.spaceteam.firecal.contract.api.CalendarData;
import ie.ucd.spaceteam.firecal.contract.api.ErrorCode;
import ie.ucd.spaceteam.firecal.contract.data.HotspotRow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** monthly.csv and hotspots.csv. First line of every export is a "#" credit line (NASA FIRMS + version). */
@Service
public class CsvExportService {

    static final int MAX_HOTSPOT_ROWS = 100_000;
    static final String MONTHLY_HEADER = "year,month,mec,raw_count,raw_sensor,median_same_month,mean_same_month,"
            + "anomaly_pct,ci_low,ci_high,source_flag,status,aoi_area_km2";

    private final FireQueryService query;
    private final DataStoreLoader loader;
    private final GeometryFactory gf = new GeometryFactory();

    public CsvExportService(FireQueryService query, DataStoreLoader loader) {
        this.query = query;
        this.loader = loader;
    }

    public static String credit(DataStore s) {
        return "# Source: NASA FIRMS (https://firms.modaps.eosdis.nasa.gov), MODIS Aqua C6.1 + VIIRS NOAA-20; "
                + "harmonization_version=" + s.harmonizationVersion();
    }

    public void monthly(AoiRequest req, Writer out) throws IOException {
        DataStore s = query.store();
        CalendarData cal = query.calendarData(s, req);
        out.write(credit(s) + "\n" + MONTHLY_HEADER + "\n");
        for (CalendarCell c : cal.cells()) {
            out.write(String.join(",", String.valueOf(c.ym().getYear()), String.valueOf(c.ym().getMonthValue()),
                    v(c.mec()), v(c.rawCount()), v(c.rawSensor()), v(c.median()), v(c.mean()), v(c.anomalyPct()),
                    v(c.ciLow()), v(c.ciHigh()), v(c.sourceFlag()), c.status().name().toLowerCase(),
                    v(cal.aoiAreaKm2())));
            out.write("\n");
        }
    }

    /** Filters first (so the 100,000-row limit can still become a 400), then writes. */
    public List<String> hotspotLines(AoiRequest req, DataStore s) {
        ResolvedAoi aoi = query.resolve(req, s);
        PreparedGeometry area = PreparedGeometryFactory.prepare(aoi.geometry());
        String from = req.start().atDay(1).toString();
        String to = req.end().atEndOfMonth().toString();
        int minConf = RequestChecks.minConf(req).ordinal();
        boolean withStatic = RequestChecks.includeStatic(req);
        String version = "," + s.harmonizationVersion();

        List<String> rows = new ArrayList<>();
        for (int year = req.start().getYear(); year <= req.end().getYear(); year++) {
            Resource r = loader.resource(s.location() + "hotspots/" + year + ".csv");
            if (!r.exists()) {
                continue; // year outside coverage: nothing to export
            }
            try (BufferedReader in = new BufferedReader(new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
                Columns col = new Columns(in.readLine());
                for (String line; (line = in.readLine()) != null; ) {
                    String[] f = line.split(",", -1);
                    String date = f[col.date];
                    if (date.compareTo(from) < 0 || date.compareTo(to) > 0) {
                        continue;
                    }
                    if (ConfidenceClass.valueOf(f[col.conf].toUpperCase()).ordinal() < minConf) {
                        continue;
                    }
                    if (!withStatic && ("1".equals(f[col.type]) || "2".equals(f[col.type]))) {
                        continue;
                    }
                    double lat = Double.parseDouble(f[col.lat]);
                    double lon = Double.parseDouble(f[col.lon]);
                    if (!area.covers(gf.createPoint(new Coordinate(lon, lat)))) {
                        continue;
                    }
                    if (rows.size() == MAX_HOTSPOT_ROWS) {
                        throw new ApiException(ErrorCode.AOI_TOO_LARGE, HttpStatus.BAD_REQUEST,
                                "more than 100,000 hotspots; choose a smaller area or period");
                    }
                    rows.add(line + version);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return rows;
    }

    public static String hotspotHeader() {
        return String.join(",", HotspotRow.COLUMNS) + ",harmonization_version";
    }

    private static String v(Object o) {
        return o == null ? "" : Objects.toString(o);
    }

    /** Column positions read from the file header (never by fixed index). */
    private static final class Columns {
        final int date;
        final int conf;
        final int type;
        final int lat;
        final int lon;

        Columns(String header) {
            if (header == null) {
                throw new IllegalStateException("empty hotspot file");
            }
            List<String> h = List.of(header.split(","));
            date = idx(h, "acq_date");
            conf = idx(h, "confidence_class");
            type = idx(h, "type");
            lat = idx(h, "latitude");
            lon = idx(h, "longitude");
        }

        private static int idx(List<String> h, String name) {
            int i = h.indexOf(name);
            if (i < 0) {
                throw new IllegalStateException("hotspot file is missing column " + name);
            }
            return i;
        }
    }

}
