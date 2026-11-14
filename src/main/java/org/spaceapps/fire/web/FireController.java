package org.spaceapps.fire.web;

import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.spaceapps.fire.calendar.CalendarService;
import org.spaceapps.fire.client.ModelApiClient;
import org.spaceapps.fire.dto.Bbox;
import org.spaceapps.fire.dto.CalendarResponse;
import org.spaceapps.fire.dto.ForecastRequest;
import org.spaceapps.fire.dto.ForecastResponse;
import org.spaceapps.fire.forecast.ForecastService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/** The only API the TypeScript frontend talks to. */
@RestController
@RequestMapping("/api")
public class FireController {

    private final ForecastService forecasts;
    private final CalendarService calendar;
    private final ModelApiClient model;

    public FireController(ForecastService forecasts, CalendarService calendar, ModelApiClient model) {
        this.forecasts = forecasts;
        this.calendar = calendar;
        this.model = model;
    }

    /**
     * Next 1-7 days, per 0.5-degree cell: probability that one NOAA-20-equivalent VIIRS satellite detects
     * at least one hotspot that day (detection, not ignition), plus area summary.
     */
    @PostMapping("/forecast")
    public ForecastResponse forecast(@Valid @RequestBody ForecastRequest request) {
        return forecasts.forecast(request);
    }

    /** Year x month burning calendar for an area, with historical median and latest-month anomaly. */
    @GetMapping("/calendar")
    public CalendarResponse calendar(@RequestParam String bbox) {
        return calendar.calendar(Bbox.parse(bbox));
    }

    @GetMapping("/calendar.csv")
    public ResponseEntity<String> calendarCsv(@RequestParam String bbox) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"burning_calendar.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(calendar.csv(Bbox.parse(bbox)));
    }

    @GetMapping("/hotspots.csv")
    public ResponseEntity<String> hotspotsCsv(@RequestParam String bbox,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hotspots.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(calendar.hotspotsCsv(Bbox.parse(bbox), from, to));
    }

    /** How good were past forecasts? Proxied from the Python back-test. */
    @GetMapping("/accuracy")
    public Map<String, Object> accuracy(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String bbox) {
        return model.accuracy(from, to, bbox == null ? null : Bbox.parse(bbox).toQuery());
    }

    /** Observed fire cells on a date (map overlay to compare with forecasts). */
    @GetMapping("/observed")
    public Map<String, Object> observed(@RequestParam String date, @RequestParam(required = false) String bbox) {
        return model.observed(date, bbox == null ? null : Bbox.parse(bbox).toQuery());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", "ok");
        out.put("fallbackForecastAsOf", forecasts.fallbackAsOf());
        try {
            out.put("modelApi", model.health());
        } catch (RestClientException e) {
            out.put("modelApi", Map.of("status", "down", "error", String.valueOf(e.getMessage())));
        }
        return out;
    }

    /** Pull new satellite data now (same as the daily schedule). */
    @PostMapping("/admin/refresh")
    public Map<String, Object> refresh(@RequestParam(required = false) String start,
                                       @RequestParam(required = false) String end) {
        return forecasts.refresh(start, end);
    }
}
