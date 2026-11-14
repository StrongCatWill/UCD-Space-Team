package ie.ucd.space.firecal.api.device;

import static org.assertj.core.api.Assertions.assertThat;

import ie.ucd.space.firecal.api.TinyOut;
import ie.ucd.space.firecal.api.calc.AoiQueryFactory;
import ie.ucd.space.firecal.api.calc.CalendarService;
import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.geo.AoiResolver;
import ie.ucd.space.firecal.api.geo.NaBoundary;
import ie.ucd.space.firecal.api.outlook.ForecastService;
import ie.ucd.space.firecal.api.outlook.OutlookCache;
import ie.ucd.space.firecal.api.outlook.SpcFeature;
import ie.ucd.space.firecal.contract.YearRange;
import ie.ucd.space.firecal.contract.api.DevicePayload;
import ie.ucd.space.firecal.contract.api.OutlookLevel;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Envelope;

/**
 * Alert rule: alert = anomaly_pct >= 100 || official level >= ELEVATED.
 * Tiny data: October baseline 2003-2007 = 10 each -> median 10. 2026-10 = 20 -> anomaly +100.0 -> alert.
 * 2026-10 = 19.99 -> +99.9 -> no alert (unless an official ELEVATED polygon covers the area).
 */
class DeviceServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T08:00:00Z"), ZoneOffset.UTC);

    private static DeviceService service(List<SpcFeature> spc) {
        OutlookCache cache = new OutlookCache(() -> spc, true);
        cache.refresh();
        AoiQueryFactory queries = new AoiQueryFactory(new AoiResolver(NaBoundary.fromClasspath(1.0), 1_000_000), CLOCK, new YearRange(2003, 2007));
        return new DeviceService(queries, new CalendarService(), new ForecastService(cache), 100, OutlookLevel.ELEVATED);
    }

    private static DataStore store(Path dir, double oct2026) throws Exception {
        TinyOut t = new TinyOut();
        for (int y = 2003; y <= 2007; y++) t.month(y + "-10", 10);
        t.month("2026-09", 4).month("2026-10", oct2026).month("2026-11", 2);
        return t.write(dir, TinyOut.TINY_AREAS);
    }

    private static SpcFeature elevatedOverTiny() {
        return new SpcFeature("Day 1 Fire Weather Outlook", OutlookLevel.ELEVATED, "Elevated",
                Instant.parse("2026-11-14T17:00:00Z"), Instant.parse("2026-11-15T12:00:00Z"),
                new GeometryFactory().toGeometry(new Envelope(-122, -121, 39, 40)));
    }

    @Test
    void anomalyAtThresholdRaisesAlert(@TempDir Path dir) throws Exception {
        DevicePayload d = service(List.of()).device("tiny", store(dir, 20));
        assertThat(d.areaId()).isEqualTo("tiny");
        assertThat(d.areaName()).isEqualTo("Tiny");
        assertThat(d.month()).isEqualTo(YearMonth.of(2026, 10)); // latest finished month, not in-progress 2026-11
        assertThat(d.mec()).isEqualTo(20.0);
        assertThat(d.median()).isEqualTo(10.0);
        assertThat(d.anomalyPct()).isEqualTo(100.0);
        assertThat(d.alert()).isTrue();
        assertThat(d.snapshot()).isFalse();
        assertThat(d.official().level()).isEqualTo(OutlookLevel.NONE);
        assertThat(d.last12()).hasSize(12);
        assertThat(d.last12().get(11)).isEqualTo(20.0);
        assertThat(d.last12().get(10)).isEqualTo(4.0);
    }

    @Test
    void anomalyJustBelowThresholdNoAlert(@TempDir Path dir) throws Exception {
        DevicePayload d = service(List.of()).device("tiny", store(dir, 19.99));
        assertThat(d.anomalyPct()).isEqualTo(99.9);
        assertThat(d.alert()).isFalse();
    }

    @Test
    void officialElevatedRaisesAlertEvenWithLowAnomaly(@TempDir Path dir) throws Exception {
        DevicePayload d = service(List.of(elevatedOverTiny())).device("tiny", store(dir, 5));
        assertThat(d.anomalyPct()).isEqualTo(-50.0);
        assertThat(d.official().level()).isEqualTo(OutlookLevel.ELEVATED);
        assertThat(d.official().source()).isEqualTo("NOAA SPC Fire Weather Outlook");
        assertThat(d.official().issued()).isEqualTo(Instant.parse("2026-11-14T17:00:00Z"));
        assertThat(d.alert()).isTrue();
    }

    @Test
    void outlookLevelFromExperimentalEstimate(@TempDir Path dir) throws Exception {
        DevicePayload d = service(List.of()).device("tiny", store(dir, 20));
        assertThat(d.outlook().source()).isEqualTo("experimental");
        assertThat(d.outlook().level()).isIn("above_normal", "near_normal", "below_normal");
    }
}
