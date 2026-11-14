package ie.ucd.space.firecal.api.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import ie.ucd.space.firecal.api.TinyOut;
import ie.ucd.space.firecal.api.calc.AoiQuery;
import ie.ucd.space.firecal.api.calc.AoiQueryFactory;
import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.data.DataStoreLoader;
import ie.ucd.space.firecal.api.geo.AoiResolver;
import ie.ucd.space.firecal.api.geo.NaBoundary;
import ie.ucd.space.firecal.contract.YearRange;
import ie.ucd.space.firecal.contract.api.AoiRequest;
import ie.ucd.space.firecal.contract.api.DemoAoi;
import ie.ucd.space.firecal.contract.api.ExperimentalEstimate;
import ie.ucd.space.firecal.contract.api.ForecastData;
import ie.ucd.space.firecal.contract.api.OfficialStatus;
import ie.ucd.space.firecal.contract.api.OutlookLevel;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class ForecastServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T08:00:00Z"), ZoneOffset.UTC);
    static final AoiResolver RESOLVER = new AoiResolver(NaBoundary.fromClasspath(1.0), 1_000_000);
    static final YearRange BASELINE = new YearRange(2003, 2007);

    private static OutlookCache cacheWith(List<SpcFeature> features) {
        OutlookCache c = new OutlookCache(() -> features, true);
        c.refresh();
        return c;
    }

    private static List<SpcFeature> fixtureFeatures() throws Exception {
        return SpcParser.parse(1, new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(ForecastServiceTest.class.getResourceAsStream("/spc/layer1_day1.geojson")));
    }

    /**
     * Hand calc (current month 2026-11, target 2026-12):
     *   Aug/Sep/Oct baseline 2003-2007 all 10 -> medians 10 + 10 + 10 = 30
     *   2026-08/09/10 = 15 + 15 + 15 = 45  -> s = 45 / 30 = 1.5 (within [0.5, 2])
     *   Dec baseline 2, 4, 6, 8, 10 -> median 6, p25 4, p75 8, p_any 1.0
     *   expected = 6 * 1.5 = 9.0, p25 = 6.0, p75 = 12.0
     */
    @Test
    void experimentalEstimateMatchesHandCalculation(@TempDir Path dir) throws Exception {
        TinyOut t = new TinyOut();
        double[] dec = {2, 4, 6, 8, 10};
        for (int y = 2003; y <= 2007; y++) {
            for (String m : List.of("08", "09", "10")) t.month(y + "-" + m, 10);
            t.month(y + "-12", dec[y - 2003]);
        }
        for (String m : List.of("08", "09", "10")) t.month("2026-" + m, 15);
        t.month("2026-11", 1);
        DataStore store = t.write(dir, TinyOut.TINY_AREAS);

        AoiQuery q = new AoiQueryFactory(RESOLVER, CLOCK, BASELINE).from(new AoiRequest(new DemoAoi("tiny"), null, null, null, false, null), store);
        ExperimentalEstimate e = new ForecastService(cacheWith(List.of())).forecast(q, store).experimental();

        assertThat(e.targetMonth()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(e.scaleS()).isEqualTo(1.5);
        assertThat(e.expectedMec()).isEqualTo(9.0);
        assertThat(e.p25()).isEqualTo(6.0);
        assertThat(e.p75()).isEqualTo(12.0);
        assertThat(e.pAny()).isEqualTo(1.0);
        assertThat(e.label()).containsIgnoringCase("experimental");
    }

    @Test
    void scaleIsClippedToTwo(@TempDir Path dir) throws Exception {
        TinyOut t = new TinyOut();
        for (int y = 2003; y <= 2007; y++) for (String m : List.of("08", "09", "10", "12")) t.month(y + "-" + m, 10);
        for (String m : List.of("08", "09", "10")) t.month("2026-" + m, 100); // ratio 10 -> clipped to 2
        DataStore store = t.write(dir, TinyOut.TINY_AREAS);
        AoiQuery q = new AoiQueryFactory(RESOLVER, CLOCK, BASELINE).from(new AoiRequest(new DemoAoi("tiny"), null, null, null, false, null), store);
        ExperimentalEstimate e = new ForecastService(cacheWith(List.of())).forecast(q, store).experimental();
        assertThat(e.scaleS()).isEqualTo(2.0);
        assertThat(e.expectedMec()).isEqualTo(20.0);
    }

    private static AoiQuery mockQuery(String areaId) {
        DataStore store = new DataStoreLoader(new DefaultResourceLoader()).load("classpath:mock/", "classpath:config/demo_areas.json");
        return new AoiQueryFactory(RESOLVER, CLOCK, new YearRange(2003, 2025))
                .from(new AoiRequest(new DemoAoi(areaId), null, null, null, false, null), store);
    }

    private static DataStore mockStore() {
        return new DataStoreLoader(new DefaultResourceLoader()).load("classpath:mock/", "classpath:config/demo_areas.json");
    }

    @Test
    void officialHighestIntersectingLevelFirst() throws Exception {
        ForecastData f = new ForecastService(cacheWith(fixtureFeatures())).forecast(mockQuery("norcal"), mockStore());
        assertThat(f.officialStatus()).isEqualTo(OfficialStatus.available);
        // norcal intersects the Elevated and the nested Critical polygon of the same product (not the Extremely
        // Critical one in New Mexico) -> one entry per product, at its highest level
        assertThat(f.official()).extracting(o -> o.level()).containsExactly(OutlookLevel.CRITICAL);
        assertThat(f.official().get(0).levelLabel()).isEqualTo("Critical");
        assertThat(f.official().get(0).validTo()).isEqualTo(Instant.parse("2026-11-15T12:00:00Z"));
        assertThat(f.official().get(0).provider()).isEqualTo("NOAA SPC");
        assertThat(f.official().get(0).link()).startsWith("https://www.spc.noaa.gov/");
    }

    @Test
    void officialStatusVariants() throws Exception {
        // Mexico has no SPC coverage
        assertThat(new ForecastService(cacheWith(fixtureFeatures())).forecast(mockQuery("mx_sierra"), mockStore()).officialStatus())
                .isEqualTo(OfficialStatus.no_feed);
        // US area but no polygon intersects
        assertThat(new ForecastService(cacheWith(List.of())).forecast(mockQuery("norcal"), mockStore()).officialStatus())
                .isEqualTo(OfficialStatus.none_intersecting);
        // never fetched successfully
        OutlookCache failing = new OutlookCache(() -> { throw new IllegalStateException("down"); }, true);
        failing.refresh();
        ForecastData f = new ForecastService(failing).forecast(mockQuery("norcal"), mockStore());
        assertThat(f.officialStatus()).isEqualTo(OfficialStatus.upstream_unavailable);
        assertThat(f.experimental()).isNotNull(); // our estimate still served, never blended
    }

    @Test
    void cacheKeepsLastGoodCopyWhenUpstreamFails() throws Exception {
        List<SpcFeature> good = fixtureFeatures();
        AtomicBoolean fail = new AtomicBoolean(false);
        OutlookCache c = new OutlookCache(() -> {
            if (fail.get()) throw new IllegalStateException("SPC down");
            return good;
        }, true);
        c.refresh();
        var first = c.current().orElseThrow();
        fail.set(true);
        c.refresh();
        assertThat(c.current()).containsSame(first);
        assertThat(c.lastError()).contains("SPC down");
    }
}
