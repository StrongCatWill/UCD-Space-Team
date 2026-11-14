package ie.ucd.spaceteam.firecal.api.web;

import ie.ucd.spaceteam.firecal.api.service.CsvExportService;
import ie.ucd.spaceteam.firecal.api.service.FireQueryService;
import ie.ucd.spaceteam.firecal.api.store.DataStore;
import ie.ucd.spaceteam.firecal.contract.api.AoiRequest;
import ie.ucd.spaceteam.firecal.contract.api.CalendarData;
import ie.ucd.spaceteam.firecal.contract.api.DevicePayload;
import ie.ucd.spaceteam.firecal.contract.api.Envelope;
import ie.ucd.spaceteam.firecal.contract.api.ForecastData;
import ie.ucd.spaceteam.firecal.contract.api.MetaData;
import ie.ucd.spaceteam.firecal.contract.api.SummaryData;
import io.swagger.v3.oas.annotations.Operation;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final FireQueryService query;
    private final CsvExportService csv;

    public ApiController(FireQueryService query, CsvExportService csv) {
        this.query = query;
        this.csv = csv;
    }

    @Operation(summary = "Monthly MEC calendar for an AOI (one cell per month, no_data where not covered)")
    @PostMapping("/calendar")
    public Envelope<CalendarData> calendar(@RequestBody AoiRequest req) {
        return query.calendar(req);
    }

    @Operation(summary = "Summary cards, seasonality, yearly totals and top anomalies")
    @PostMapping("/summary")
    public Envelope<SummaryData> summary(@RequestBody AoiRequest req) {
        return query.summary(req);
    }

    @Operation(summary = "Official outlooks (separate) and our experimental next-month estimate")
    @PostMapping("/forecast")
    public Envelope<ForecastData> forecast(@RequestBody AoiRequest req) {
        return query.forecast(req);
    }

    @Operation(summary = "Monthly series as CSV")
    @PostMapping(value = "/monthly.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> monthlyCsv(@RequestBody AoiRequest req) {
        query.resolve(req, query.store()); // validate before the response starts
        return csvResponse("monthly.csv", os -> {
            Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
            csv.monthly(req, w);
            w.flush();
        });
    }

    @Operation(summary = "Normalized hotspots inside the AOI and period as CSV (max 100,000 rows)")
    @PostMapping(value = "/hotspots.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> hotspotsCsv(@RequestBody AoiRequest req) {
        DataStore s = query.store();
        List<String> rows = csv.hotspotLines(req, s);
        return csvResponse("hotspots.csv", os -> {
            Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
            w.write(CsvExportService.credit(s) + "\n" + CsvExportService.hotspotHeader() + "\n");
            for (String r : rows) {
                w.write(r);
                w.write('\n');
            }
            w.flush();
        });
    }

    @Operation(summary = "Raspberry Pi payload (no envelope); ?snapshot=true serves the frozen demo snapshot")
    @GetMapping("/device/{area_id}")
    public DevicePayload device(@PathVariable("area_id") String areaId,
                                @RequestParam(name = "snapshot", defaultValue = "false") boolean snapshot) {
        return query.device(areaId, snapshot);
    }

    @Operation(summary = "Switch month, calibration quality, coverage and demo areas")
    @GetMapping("/meta")
    public Envelope<MetaData> meta() {
        return query.meta();
    }

    private static ResponseEntity<StreamingResponseBody> csvResponse(String name, StreamingResponseBody body) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(body);
    }
}
