package ie.ucd.space.firecal.api.calc;

import static org.assertj.core.api.Assertions.assertThat;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.data.DataStoreLoader;
import ie.ucd.space.firecal.api.geo.AoiResolver;
import ie.ucd.space.firecal.api.geo.NaBoundary;
import ie.ucd.space.firecal.contract.YearRange;
import ie.ucd.space.firecal.contract.api.AoiRequest;
import ie.ucd.space.firecal.contract.api.CalendarCell;
import ie.ucd.space.firecal.contract.api.CalendarData;
import ie.ucd.space.firecal.contract.api.CircleAoi;
import ie.ucd.space.firecal.contract.api.MonthStatus;
import ie.ucd.space.firecal.contract.api.SummaryData;
import ie.ucd.space.firecal.contract.SourceFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Hand-calculated AOI aggregation on a tiny out/ written to a temp dir.
 *
 * Two cells inside a 30 km circle: A = 395_-1215 (39.55,-121.45), B = 396_-1214 (39.65,-121.35); region B_35_-125.
 * July MEC per year (A + B = AOI sum):
 *   2003: 10 + 5 = 15    2004: 20 + 5 = 25    2005: (absent = 0) + 5 = 5    2006: 40 + 5 = 45    2007: 30 + 7 = 37
 * Baseline 2003-2007, July: sorted [5, 15, 25, 37, 45] -> median 25, mean 127/5 = 25.4,
 *   p25 = 15 (pos 1), p75 = 37 (pos 3), p_any = 1.0
 * anomaly_pct      = (mec - 25) / 25 * 100   -> 2003 -40.0 | 2004 0.0 | 2005 -80.0 | 2006 80.0 | 2007 48.0
 * anomaly_pct_mean = (mec - 25.4)/25.4*100   -> 2003 -40.9 | 2004 -1.6 | 2005 -80.3 | 2006 77.2 | 2007 45.7
 * 2008-07: A 50 -> 50 -> anomaly 100.0
 * 2025-07 (VIIRS_CAL, a = 0.3, a_p25 = 0.24, a_p75 = 0.39): A mec 30 -> ci_low 30*0.24/0.3 = 24.0, ci_high 39.0
 */
class CalendarHandCalcTest {

    @TempDir
    static Path out;
    static DataStore store;
    static AoiQueryFactory queries;
    static CalendarService calendar;
    static SummaryService summary;

    static final CircleAoi AOI = new CircleAoi(39.6, -121.4, 30);
    static final YearRange BASELINE = new YearRange(2003, 2007);

    @BeforeAll
    static void writeTinyOut() throws Exception {
        Files.writeString(out.resolve("calibration.json"), """
                {"harmonization_version":"test-h1","generated_at":"2026-11-14T08:00:00Z","train":{"from":2018,"to":2023},
                 "switch_month":"2025-01","series_start":"2003-01",
                 "regions":[{"region_id":"B_35_-125","parent":"United_States","month":7,"a":0.3,"a_p25":0.24,"a_p75":0.39,"n_years":6,"level":"region"}],
                 "country":[],"north_america":[],
                 "validation":{"holdout":{"from":2024,"to":2025},"mape":10.0,"corr":0.9,"by_country":{}}}""");
        Files.writeString(out.resolve("coverage.json"), """
                {"MODIS_AQUA":{"from":"2002-07-04","sp_until":"2026-08-31"},"VIIRS_NOAA20":{"from":"2018-01-01","sp_until":"2026-06-30"}}""");
        List<String> rows = new ArrayList<>();
        Function<Object[], String> a = v -> cell("395_-1215", 39.55, -121.45, v);
        Function<Object[], String> b = v -> cell("396_-1214", 39.65, -121.35, v);
        rows.add(a.apply(new Object[]{"2003-07", 10}));
        rows.add(a.apply(new Object[]{"2004-07", 20}));
        rows.add(a.apply(new Object[]{"2006-07", 40}));
        rows.add(a.apply(new Object[]{"2007-07", 30}));
        rows.add(b.apply(new Object[]{"2003-07", 5}));
        rows.add(b.apply(new Object[]{"2004-07", 5}));
        rows.add(b.apply(new Object[]{"2005-07", 5}));
        rows.add(b.apply(new Object[]{"2006-07", 5}));
        rows.add(b.apply(new Object[]{"2007-07", 7}));
        rows.add(a.apply(new Object[]{"2008-07", 50}));
        rows.add(viirs("2025-07", 30.0, 100, false));
        rows.add(viirs("2026-08", 12.0, 40, true));
        rows.add(viirs("2026-11", 3.0, 10, true));
        Files.writeString(out.resolve("cell_month.json"), "[" + String.join(",", rows) + "]");
        Path areas = out.resolve("demo_areas.json");
        Files.writeString(areas, "[]");

        store = new DataStoreLoader(new DefaultResourceLoader()).load(out.toUri().toString(), areas.toUri().toString());
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T08:00:00Z"), ZoneOffset.UTC);
        queries = new AoiQueryFactory(new AoiResolver(NaBoundary.fromClasspath(1.0), 1_000_000), clock, new YearRange(2003, 2025));
        calendar = new CalendarService();
        summary = new SummaryService();
    }

    private static String cell(String id, double lat, double lon, Object[] v) {
        return String.format(java.util.Locale.ROOT,
                "{\"cell_id\":\"%s\",\"lat\":%s,\"lon\":%s,\"parent\":\"United_States\",\"ym\":\"%s\",\"mec\":%s,"
                        + "\"mec_with_static\":%s,\"raw_count\":%s,\"raw_sensor\":\"MODIS_AQUA\",\"source_flag\":\"MODIS\",\"provisional\":false}",
                id, lat, lon, v[0], v[1], v[1], v[1]);
    }

    private static String viirs(String ym, double mec, int raw, boolean provisional) {
        return String.format(java.util.Locale.ROOT,
                "{\"cell_id\":\"395_-1215\",\"lat\":39.55,\"lon\":-121.45,\"parent\":\"United_States\",\"ym\":\"%s\",\"mec\":%s,"
                        + "\"mec_with_static\":%s,\"raw_count\":%d,\"raw_sensor\":\"VIIRS_NOAA20\",\"source_flag\":\"VIIRS_CAL\",\"provisional\":%s}",
                ym, mec, mec, raw, provisional);
    }

    private static Map<YearMonth, CalendarCell> calendar(String start, String end) {
        AoiQuery q = queries.from(new AoiRequest(AOI, YearMonth.parse(start), YearMonth.parse(end), BASELINE, false, null), store);
        CalendarData d = calendar.calendar(q, store);
        return d.cells().stream().collect(Collectors.toMap(CalendarCell::ym, c -> c));
    }

    @Test
    void anomalyPctForFiveJulysMatchesHandCalculation() {
        Map<YearMonth, CalendarCell> c = calendar("2003-01", "2008-12");
        double[][] expected = { // year, mec, anomaly_pct, anomaly_pct_mean
                {2003, 15, -40.0, -40.9}, {2004, 25, 0.0, -1.6}, {2005, 5, -80.0, -80.3},
                {2006, 45, 80.0, 77.2}, {2007, 37, 48.0, 45.7}};
        for (double[] e : expected) {
            CalendarCell cell = c.get(YearMonth.of((int) e[0], 7));
            assertThat(cell.mec()).as("mec %s", e[0]).isEqualTo(e[1]);
            assertThat(cell.median()).isEqualTo(25.0);
            assertThat(cell.mean()).isEqualTo(25.4);
            assertThat(cell.anomalyPct()).as("anomaly %s", e[0]).isEqualTo(e[2]);
            assertThat(cell.anomalyPctMean()).as("anomaly_mean %s", e[0]).isEqualTo(e[3]);
            assertThat(cell.status()).isEqualTo(MonthStatus.ok);
            assertThat(cell.sourceFlag()).isEqualTo(SourceFlag.MODIS);
            assertThat(cell.ciLow()).isNull();
        }
        assertThat(c.get(YearMonth.of(2008, 7)).anomalyPct()).isEqualTo(100.0);
    }

    @Test
    void zeroMonthInCoverageIsZeroNotNoData_andMedianZeroGivesNullAnomaly() {
        CalendarCell jan = calendar("2003-01", "2003-12").get(YearMonth.of(2003, 1));
        assertThat(jan.status()).isEqualTo(MonthStatus.ok);
        assertThat(jan.mec()).isEqualTo(0.0);
        assertThat(jan.median()).isEqualTo(0.0);
        assertThat(jan.anomalyPct()).isNull();
    }

    @Test
    void monthsBeforeSeriesStartAndAfterDataAreNoData() {
        Map<YearMonth, CalendarCell> c = calendar("2002-11", "2027-01");
        CalendarCell before = c.get(YearMonth.of(2002, 12));
        assertThat(before.status()).isEqualTo(MonthStatus.no_data);
        assertThat(before.mec()).isNull();
        assertThat(before.median()).isNull();
        assertThat(c.get(YearMonth.of(2026, 12)).status()).isEqualTo(MonthStatus.no_data);
    }

    @Test
    void viirsMonthsCarryCalibrationIntervalAndStatus() {
        Map<YearMonth, CalendarCell> c = calendar("2025-01", "2026-11");
        CalendarCell jul = c.get(YearMonth.of(2025, 7));
        assertThat(jul.sourceFlag()).isEqualTo(SourceFlag.VIIRS_CAL);
        assertThat(jul.mec()).isEqualTo(30.0);
        assertThat(jul.rawCount()).isEqualTo(100);
        assertThat(jul.ciLow()).isEqualTo(24.0);
        assertThat(jul.ciHigh()).isEqualTo(39.0);
        assertThat(jul.status()).isEqualTo(MonthStatus.ok);
        assertThat(c.get(YearMonth.of(2026, 8)).status()).isEqualTo(MonthStatus.provisional);
        assertThat(c.get(YearMonth.of(2026, 11)).status()).isEqualTo(MonthStatus.in_progress);
    }

    @Test
    void summaryCardsSeasonalityAndYearly() {
        AoiQuery q = queries.from(new AoiRequest(AOI, YearMonth.parse("2003-01"), YearMonth.parse("2008-12"), BASELINE, false, null), store);
        SummaryData s = summary.summary(q, store);
        // total = 15 + 25 + 5 + 45 + 37 + 50 = 177
        assertThat(s.cards().totalMec()).isEqualTo(177.0);
        assertThat(s.cards().peakMonth().ym()).isEqualTo(YearMonth.of(2008, 7));
        assertThat(s.cards().peakYear().year()).isEqualTo(2008);
        assertThat(s.cards().thisMonth().ym()).isEqualTo(YearMonth.of(2008, 12));
        assertThat(s.seasonality()).hasSize(12);
        var july = s.seasonality().get(6);
        assertThat(july.month()).isEqualTo(7);
        assertThat(july.median()).isEqualTo(25.0);
        assertThat(july.p25()).isEqualTo(15.0);
        assertThat(july.p75()).isEqualTo(37.0);
        assertThat(july.mean()).isEqualTo(25.4);
        assertThat(july.thisYear()).isEqualTo(50.0);
        assertThat(s.yearly()).hasSize(6).allSatisfy(y -> assertThat(y.complete()).isTrue());
        // yearly mean = 177 / 6 = 29.5
        assertThat(s.yearlyMean()).isEqualTo(29.5);
        assertThat(s.topAnomalies().get(0).ym()).isEqualTo(YearMonth.of(2008, 7));
        assertThat(s.topAnomalies().get(0).anomalyPct()).isEqualTo(100.0);
    }

    @Test
    void baselineExcludesProvisionalAndCurrentMonths() {
        // baseline 2026-2026 has only provisional/in_progress/no_data months -> no usable baseline -> null median
        AoiQuery q = queries.from(new AoiRequest(AOI, YearMonth.parse("2026-08"), YearMonth.parse("2026-08"),
                new YearRange(2026, 2026), false, null), store);
        CalendarCell aug = calendar.calendar(q, store).cells().get(0);
        assertThat(aug.mec()).isEqualTo(12.0);
        assertThat(aug.median()).isNull();
        assertThat(aug.anomalyPct()).isNull();
    }
}
