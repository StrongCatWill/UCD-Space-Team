package ie.ucd.space.firecal.api.calc;

import ie.ucd.space.firecal.api.config.FireProperties;
import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.web.ApiException;
import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.files.HotspotCsv;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Reads out/hotspots/{year}.csv row by row (streaming) and keeps rows inside the AOI and period, with
 * confidence_class >= min_conf and, unless include_static, without FIRMS type 1/2 (volcano / static land source).
 * More than fire.hotspot-row-limit matching rows -> 400 AOI_TOO_LARGE (checked before anything is written).
 */
@Service
public class HotspotExportService {

    private static final Logger log = LoggerFactory.getLogger(HotspotExportService.class);
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();

    private final ResourceLoader resources;
    private final int rowLimit;

    public HotspotExportService(ResourceLoader resources, FireProperties props) {
        this.resources = resources;
        this.rowLimit = props.hotspotRowLimit();
    }

    /** Matching rows in contract column order (HotspotCsv.COLUMNS). */
    public List<String[]> rows(AoiQuery q, DataStore store) {
        List<String[]> out = new ArrayList<>();
        for (int year = q.start().getYear(); year <= q.end().getYear(); year++) {
            Resource r = resources.getResource(store.dataDir() + "hotspots/" + year + ".csv");
            if (!r.exists()) {
                log.debug("no hotspot file for {}", year);
                continue;
            }
            readYear(r, year, q, out);
        }
        return out;
    }

    private void readYear(Resource r, int year, AoiQuery q, List<String[]> out) {
        String file = "hotspots/" + year + ".csv";
        try (Reader reader = new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            Map<String, Integer> idx = parser.getHeaderMap();
            for (String col : HotspotCsv.COLUMNS) {
                if (!idx.containsKey(col)) throw ApiException.upstream(file + ": missing column " + col);
            }
            int iLat = idx.get("latitude"), iLon = idx.get("longitude"), iDate = idx.get("acq_date"),
                    iConf = idx.get("confidence_class"), iType = idx.get("type");
            for (CSVRecord rec : parser) {
                LocalDate date = LocalDate.parse(rec.get(iDate));
                YearMonth ym = YearMonth.from(date);
                if (ym.isBefore(q.start())) continue;
                if (ym.isAfter(q.end())) break; // files are sorted by acq_date
                if (!q.includeStatic() && isStatic(rec.get(iType))) continue;
                if (ConfidenceClass.valueOf(rec.get(iConf)).compareTo(q.minConf()) < 0) continue;
                if (!q.aoi().contains(Double.parseDouble(rec.get(iLat)), Double.parseDouble(rec.get(iLon)))) continue;
                if (out.size() >= rowLimit) {
                    throw ApiException.tooLarge("more than " + rowLimit + " hotspots match; narrow the area or period");
                }
                String[] row = new String[HotspotCsv.COLUMNS.size()];
                for (int i = 0; i < row.length; i++) row[i] = rec.get(idx.get(HotspotCsv.COLUMNS.get(i)));
                out.add(row);
            }
        } catch (IOException | DateTimeParseException | IllegalArgumentException e) {
            throw ApiException.upstream(file + ": unreadable (" + e.getMessage() + ")");
        }
    }

    private static boolean isStatic(String type) {
        return "1".equals(type) || "2".equals(type);
    }
}
