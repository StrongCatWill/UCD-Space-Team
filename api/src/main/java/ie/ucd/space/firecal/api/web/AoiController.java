package ie.ucd.space.firecal.api.web;

import ie.ucd.space.firecal.api.calc.AoiQuery;
import ie.ucd.space.firecal.api.calc.AoiQueryFactory;
import ie.ucd.space.firecal.api.calc.CalendarService;
import ie.ucd.space.firecal.api.calc.HotspotExportService;
import ie.ucd.space.firecal.api.calc.SummaryService;
import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.data.DataStoreHolder;
import ie.ucd.space.firecal.contract.api.AoiRequest;
import ie.ucd.space.firecal.contract.api.CalendarCell;
import ie.ucd.space.firecal.contract.api.CalendarData;
import ie.ucd.space.firecal.contract.api.Envelope;
import ie.ucd.space.firecal.contract.api.MonthlyCsv;
import ie.ucd.space.firecal.contract.api.SummaryData;
import ie.ucd.space.firecal.contract.files.HotspotCsv;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** AOI endpoints: calendar, summary, monthly.csv. */
@RestController
public class AoiController {

    private final DataStoreHolder holder;
    private final AoiQueryFactory queries;
    private final CalendarService calendar;
    private final SummaryService summary;
    private final EnvelopeFactory envelopes;
    private final HotspotExportService hotspots;

    public AoiController(DataStoreHolder holder, AoiQueryFactory queries, CalendarService calendar,
                         SummaryService summary, EnvelopeFactory envelopes, HotspotExportService hotspots) {
        this.hotspots = hotspots;
        this.holder = holder;
        this.queries = queries;
        this.calendar = calendar;
        this.summary = summary;
        this.envelopes = envelopes;
    }

    @Operation(summary = "Monthly MEC calendar for an AOI, with baseline median/mean and anomalies")
    @PostMapping("/api/calendar")
    public Envelope<CalendarData> calendar(@RequestBody AoiRequest req) {
        DataStore s = holder.current();
        return envelopes.wrap(s, calendar.calendar(queries.from(req, s), s));
    }

    @Operation(summary = "Summary cards, seasonality, yearly totals and top anomalies for an AOI")
    @PostMapping("/api/summary")
    public Envelope<SummaryData> summary(@RequestBody AoiRequest req) {
        DataStore s = holder.current();
        return envelopes.wrap(s, summary.summary(queries.from(req, s), s));
    }

    @Operation(summary = "Monthly calendar as CSV (first line is a # credit comment)",
            responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv")))
    @PostMapping("/api/monthly.csv")
    public void monthlyCsv(@RequestBody AoiRequest req, HttpServletResponse resp) throws IOException {
        DataStore s = holder.current();
        AoiQuery q = queries.from(req, s);
        CalendarData d = calendar.calendar(q, s);
        String version = s.calibration().harmonizationVersion();
        String comment = "Data: NASA FIRMS (MODIS C6.1 Aqua; VIIRS NOAA-20 C2 calibrated to MODIS-equivalent counts from "
                + d.switchMonth() + "). harmonization_version=" + version + ". as_of=" + s.asOf();
        try (CSVPrinter p = CsvSupport.open(resp, "monthly_" + q.start() + "_" + q.end() + ".csv", version, comment, MonthlyCsv.COLUMNS)) {
            for (CalendarCell c : d.cells()) {
                p.printRecord(c.ym().getYear(), c.ym().getMonthValue(), CsvSupport.str(c.mec()), CsvSupport.str(c.rawCount()),
                        CsvSupport.str(c.rawSensor()), CsvSupport.str(c.median()), CsvSupport.str(c.mean()),
                        CsvSupport.str(c.anomalyPct()), CsvSupport.str(c.ciLow()), CsvSupport.str(c.ciHigh()),
                        CsvSupport.str(c.sourceFlag()), c.status(), d.aoiAreaKm2());
            }
        }
    }

    @Operation(summary = "Hotspot detections in the AOI and period as CSV (contract columns + harmonization_version)",
            responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv")))
    @PostMapping("/api/hotspots.csv")
    public void hotspotsCsv(@RequestBody AoiRequest req, HttpServletResponse resp) throws IOException {
        DataStore s = holder.current();
        AoiQuery q = queries.from(req, s);
        List<String[]> rows = hotspots.rows(q, s); // throws AOI_TOO_LARGE before the response is committed
        String version = s.calibration().harmonizationVersion();
        List<String> header = new ArrayList<>(HotspotCsv.COLUMNS);
        header.add("harmonization_version");
        String comment = "Data: NASA FIRMS active fire detections (MODIS C6.1, VIIRS NOAA-20 C2). harmonization_version="
                + version + ". min_conf=" + q.minConf() + ", include_static=" + q.includeStatic() + ". rows=" + rows.size();
        try (CSVPrinter p = CsvSupport.open(resp, "hotspots_" + q.start() + "_" + q.end() + ".csv", version, comment, header)) {
            for (String[] row : rows) {
                String[] withVersion = Arrays.copyOf(row, row.length + 1);
                withVersion[row.length] = version;
                p.printRecord((Object[]) withVersion);
            }
        }
    }
}
