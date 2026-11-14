package ie.ucd.spaceteam.firecal.api.service;

import ie.ucd.spaceteam.firecal.api.config.FireProperties;
import ie.ucd.spaceteam.firecal.api.store.DataStore;
import ie.ucd.spaceteam.firecal.api.store.DataStoreHolder;
import ie.ucd.spaceteam.firecal.api.web.ApiException;
import ie.ucd.spaceteam.firecal.contract.SourceFlag;
import ie.ucd.spaceteam.firecal.contract.api.AoiRequest;
import ie.ucd.spaceteam.firecal.contract.api.CalendarCell;
import ie.ucd.spaceteam.firecal.contract.api.CalendarData;
import ie.ucd.spaceteam.firecal.contract.api.CalibrationMeta;
import ie.ucd.spaceteam.firecal.contract.api.CellStatus;
import ie.ucd.spaceteam.firecal.contract.api.DataSourceInfo;
import ie.ucd.spaceteam.firecal.contract.api.DevicePayload;
import ie.ucd.spaceteam.firecal.contract.api.Envelope;
import ie.ucd.spaceteam.firecal.contract.api.ErrorCode;
import ie.ucd.spaceteam.firecal.contract.api.ForecastData;
import ie.ucd.spaceteam.firecal.contract.api.MetaData;
import ie.ucd.spaceteam.firecal.contract.api.OfficialStatus;
import ie.ucd.spaceteam.firecal.contract.api.SummaryData;
import ie.ucd.spaceteam.firecal.contract.data.Calibration;
import ie.ucd.spaceteam.firecal.contract.data.DemoArea;
import ie.ucd.spaceteam.firecal.contract.data.DemoFile;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Answers every query from the current {@link DataStore}. Demo AOIs are served from demo/{area_id}.json.
 * Custom AOIs: in the mock profile ({@code fire.mock-fallback=true}) they are answered with the demo area that
 * overlaps them most; real aggregation over cell_month.json is D2 task 4.
 */
@Service
public class FireQueryService {

    private static final Logger log = LoggerFactory.getLogger(FireQueryService.class);
    private static final DataSourceInfo MODIS_SP = new DataSourceInfo("NASA FIRMS MODIS C6.1 (Aqua)", "SP");
    private static final DataSourceInfo VIIRS_SP = new DataSourceInfo("NASA FIRMS VIIRS NOAA-20", "SP");
    private static final DataSourceInfo VIIRS_NRT = new DataSourceInfo("NASA FIRMS VIIRS NOAA-20", "NRT");

    private final DataStoreHolder holder;
    private final AoiResolver aois;
    private final FireProperties props;
    private final boolean mockFallback;
    private final GeometryFactory gf = new GeometryFactory();

    public FireQueryService(DataStoreHolder holder, AoiResolver aois, FireProperties props,
                            @Value("${fire.mock-fallback:false}") boolean mockFallback) {
        this.holder = holder;
        this.aois = aois;
        this.props = props;
        this.mockFallback = mockFallback;
    }

    // ------------------------------------------------------------------ calendar

    public Envelope<CalendarData> calendar(AoiRequest req) {
        DataStore s = holder.get();
        CalendarData data = calendarData(s, req);
        return envelope(s, data, sourcesFor(data.cells()));
    }

    public CalendarData calendarData(DataStore s, AoiRequest req) {
        RequestChecks.period(req);
        ResolvedAoi aoi = aois.resolve(req.aoi(), s);
        CalendarData demo = demoFile(s, aoi).calendar();
        Map<YearMonth, CalendarCell> byYm = demo.cells().stream()
                .collect(Collectors.toMap(CalendarCell::ym, Function.identity()));
        List<CalendarCell> cells = new ArrayList<>();
        for (YearMonth ym = req.start(); !ym.isAfter(req.end()); ym = ym.plusMonths(1)) {
            CalendarCell c = byYm.get(ym);
            cells.add(c != null ? c : noData(ym));
        }
        double area = aoi.isDemo() ? demo.aoiAreaKm2() : round1(aoi.areaKm2());
        return new CalendarData(area, s.calibration().switchMonth(), cells);
    }

    private static CalendarCell noData(YearMonth ym) {
        return new CalendarCell(ym, null, null, null, null, null, null, null, null, null, null, CellStatus.NO_DATA);
    }

    // ------------------------------------------------------------------ summary / forecast

    public Envelope<SummaryData> summary(AoiRequest req) {
        DataStore s = holder.get();
        RequestChecks.period(req);
        DemoFile f = demoFile(s, aois.resolve(req.aoi(), s));
        return envelope(s, f.summary(), sourcesFor(f.calendar().cells()));
    }

    public Envelope<ForecastData> forecast(AoiRequest req) {
        DataStore s = holder.get();
        RequestChecks.period(req);
        ResolvedAoi aoi = aois.resolve(req.aoi(), s);
        String areaId = aoi.isDemo() ? aoi.demoAreaId() : fallbackArea(s, aoi).areaId();
        ForecastData f = s.mockForecasts().get(areaId);
        if (f == null) {
            f = new ForecastData(OfficialStatus.UPSTREAM_UNAVAILABLE, List.of(), null);
        }
        return envelope(s, f, List.of(MODIS_SP, VIIRS_SP));
    }

    // ------------------------------------------------------------------ device / meta

    public DevicePayload device(String areaId, boolean snapshot) {
        DataStore s = holder.get();
        if (snapshot) {
            return s.snapshot();
        }
        DemoArea area = s.demoArea(areaId)
                .orElseThrow(() -> ApiException.badInput("unknown demo area_id: " + areaId));
        DevicePayload d = s.demoFiles().get(area.areaId()).device();
        return new DevicePayload(d.areaId(), d.areaName(), d.asOf(), d.month(), d.mec(), d.median(), d.anomalyPct(),
                d.last12(), d.outlook(), d.official(), alert(d), false);
    }

    /** alert = anomaly_pct >= threshold || official level in the configured alert levels. */
    boolean alert(DevicePayload d) {
        boolean anomaly = d.anomalyPct() != null && d.anomalyPct() >= props.anomalyAlertPct();
        boolean official = d.official() != null && d.official().level() != null
                && props.alertLevels().contains(d.official().level());
        return anomaly || official;
    }

    public Envelope<MetaData> meta() {
        DataStore s = holder.get();
        Calibration c = s.calibration();
        var v = c.validation();
        var cal = new CalibrationMeta(new ie.ucd.spaceteam.firecal.contract.data.YearRange(
                c.train().from(), v.holdout().to()), c.train(), v.holdout(), v.mape(), v.corr());
        MetaData m = new MetaData(c.switchMonth(), c.seriesStart(), cal, s.coverage(), s.demoAreas());
        return envelope(s, m, List.of(MODIS_SP, VIIRS_SP, VIIRS_NRT));
    }

    // ------------------------------------------------------------------ helpers

    public DataStore store() {
        return holder.get();
    }

    public ResolvedAoi resolve(AoiRequest req, DataStore s) {
        RequestChecks.period(req);
        return aois.resolve(req.aoi(), s);
    }

    private DemoFile demoFile(DataStore s, ResolvedAoi aoi) {
        String id = aoi.isDemo() ? aoi.demoAreaId() : fallbackArea(s, aoi).areaId();
        return s.demoFiles().get(id);
    }

    private DemoArea fallbackArea(DataStore s, ResolvedAoi aoi) {
        if (!mockFallback) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "custom AOI aggregation is not available yet; use a demo area");
        }
        DemoArea best = s.demoAreas().stream()
                .max(Comparator.comparingDouble(a -> overlap(a, aoi.geometry())))
                .orElseThrow();
        log.debug("mock: custom AOI answered with demo area {}", best.areaId());
        return best;
    }

    private double overlap(DemoArea a, Geometry g) {
        var b = a.bbox();
        Geometry box = gf.toGeometry(new org.locationtech.jts.geom.Envelope(b.get(0), b.get(2), b.get(1), b.get(3)));
        double inter = box.intersection(g).getArea();
        return inter > 0 ? inter : -box.getCentroid().distance(g.getCentroid());
    }

    private static List<DataSourceInfo> sourcesFor(List<CalendarCell> cells) {
        Set<DataSourceInfo> out = new LinkedHashSet<>();
        for (CalendarCell c : cells) {
            if (c.sourceFlag() == SourceFlag.MODIS) {
                out.add(MODIS_SP);
            } else if (c.sourceFlag() == SourceFlag.VIIRS_CAL) {
                out.add(c.status() == CellStatus.OK ? VIIRS_SP : VIIRS_NRT);
            }
        }
        return List.copyOf(out);
    }

    private static <T> Envelope<T> envelope(DataStore s, T data, List<DataSourceInfo> sources) {
        return new Envelope<>(s.calibration().generatedAt(), s.harmonizationVersion(), sources, data);
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
