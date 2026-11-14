package ie.ucd.space.firecal.api.device;

import ie.ucd.space.firecal.api.calc.AoiQuery;
import ie.ucd.space.firecal.api.calc.AoiQueryFactory;
import ie.ucd.space.firecal.api.calc.CalendarService;
import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.outlook.ForecastService;
import ie.ucd.space.firecal.api.web.ApiException;
import ie.ucd.space.firecal.contract.api.AoiRequest;
import ie.ucd.space.firecal.contract.api.CalendarCell;
import ie.ucd.space.firecal.contract.api.DemoAoi;
import ie.ucd.space.firecal.contract.api.DeviceOfficial;
import ie.ucd.space.firecal.contract.api.DeviceOutlook;
import ie.ucd.space.firecal.contract.api.DevicePayload;
import ie.ucd.space.firecal.contract.api.ExperimentalEstimate;
import ie.ucd.space.firecal.contract.api.MonthStatus;
import ie.ucd.space.firecal.contract.api.OutlookLevel;
import ie.ucd.space.firecal.contract.files.DemoAreaConfig;
import ie.ucd.space.firecal.contract.files.DemoAreaFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GET /api/device/{area_id} for the Raspberry Pi. month = latest finished month (ok or provisional; the in-progress
 * current month would make the alert flicker). alert = anomaly_pct >= threshold || official level >= threshold level.
 * Official level is always live (SPC cache); the rest comes from out/demo/{area_id}.json when present.
 */
public class DeviceService {

    static final String SPC_SOURCE = "NOAA SPC Fire Weather Outlook";

    private final AoiQueryFactory queries;
    private final CalendarService calendar;
    private final ForecastService forecasts;
    private final double alertAnomalyPct;
    private final OutlookLevel alertLevel;

    public DeviceService(AoiQueryFactory queries, CalendarService calendar, ForecastService forecasts,
                         double alertAnomalyPct, OutlookLevel alertLevel) {
        this.queries = queries;
        this.calendar = calendar;
        this.forecasts = forecasts;
        this.alertAnomalyPct = alertAnomalyPct;
        this.alertLevel = alertLevel;
    }

    public DevicePayload snapshot(DataStore store) {
        if (store.snapshot() == null) throw ApiException.notFound("no demo_snapshot.json in the data directory");
        return store.snapshot();
    }

    public DevicePayload device(String areaId, DataStore store) {
        DemoAreaConfig area = store.demoArea(areaId).orElseThrow(() -> ApiException.notFound("unknown area_id: " + areaId));
        AoiQuery q = queries.from(new AoiRequest(new DemoAoi(areaId), null, null, null, false, null), store);
        DeviceOfficial official = official(q);

        Optional<DevicePayload> pre = store.demoFile(areaId).map(DemoAreaFile::device);
        if (pre.isPresent()) {
            DevicePayload p = pre.get();
            return new DevicePayload(p.areaId(), p.areaName(), store.asOf(), p.month(), p.mec(), p.median(), p.anomalyPct(),
                    p.last12(), p.outlook(), official, alert(p.anomalyPct(), official), false);
        }

        List<CalendarCell> cells = calendar.calendar(q, store).cells();
        int idx = cells.size() - 1;
        while (idx >= 0 && cells.get(idx).status() != MonthStatus.ok && cells.get(idx).status() != MonthStatus.provisional) idx--;
        CalendarCell m = idx >= 0 ? cells.get(idx) : null;
        List<Double> last12 = new ArrayList<>();
        for (int i = idx - 11; i <= idx; i++) last12.add(i >= 0 && idx >= 0 ? cells.get(i).mec() : null);

        Double anomaly = m == null ? null : m.anomalyPct();
        return new DevicePayload(area.areaId(), area.name(), store.asOf(), m == null ? null : m.ym(),
                m == null ? null : m.mec(), m == null ? null : m.median(), anomaly, last12,
                outlook(forecasts.experimental(q, store)), official, alert(anomaly, official), false);
    }

    boolean alert(Double anomalyPct, DeviceOfficial official) {
        boolean anomalous = anomalyPct != null && anomalyPct >= alertAnomalyPct;
        boolean officialHigh = official.level() != null && official.level().compareTo(alertLevel) >= 0;
        return anomalous || officialHigh;
    }

    private DeviceOfficial official(AoiQuery q) {
        ForecastService.Official o = forecasts.official(q);
        return switch (o.status()) {
            case available -> o.highest()
                    .map(h -> new DeviceOfficial(h.level(), SPC_SOURCE, h.issuedAt()))
                    .orElse(new DeviceOfficial(OutlookLevel.NONE, SPC_SOURCE, null));
            case none_intersecting -> new DeviceOfficial(OutlookLevel.NONE, SPC_SOURCE, null);
            case no_feed -> new DeviceOfficial(null, "no_feed", null);
            case upstream_unavailable -> new DeviceOfficial(null, "upstream_unavailable", null);
        };
    }

    /** Our estimate vs the baseline spread of the target month (p25..p75 = near normal). */
    static DeviceOutlook outlook(ExperimentalEstimate e) {
        double unscaledP25 = e.scaleS() == 0 ? 0 : e.p25() / e.scaleS();
        double unscaledP75 = e.scaleS() == 0 ? 0 : e.p75() / e.scaleS();
        String level = e.expectedMec() > unscaledP75 ? "above_normal"
                : e.expectedMec() < unscaledP25 ? "below_normal" : "near_normal";
        return new DeviceOutlook(level, "experimental");
    }
}
