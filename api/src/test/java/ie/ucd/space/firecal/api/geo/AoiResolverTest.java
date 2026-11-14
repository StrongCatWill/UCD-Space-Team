package ie.ucd.space.firecal.api.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.data.DataStoreLoader;
import ie.ucd.space.firecal.api.web.ApiException;
import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.contract.api.CircleAoi;
import ie.ucd.space.firecal.contract.api.DemoAoi;
import ie.ucd.space.firecal.contract.api.ErrorCode;
import ie.ucd.space.firecal.contract.api.GeoJsonPolygon;
import ie.ucd.space.firecal.contract.api.PolygonAoi;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class AoiResolverTest {

    static DataStore store;
    static AoiResolver resolver;

    @BeforeAll
    static void setUp() {
        store = new DataStoreLoader(new DefaultResourceLoader()).load("classpath:mock/", "classpath:config/demo_areas.json");
        resolver = new AoiResolver(NaBoundary.fromClasspath(1.0), 1_000_000);
    }

    @Test
    void demoAreaIsItsBboxWithSphericalArea() {
        ResolvedAoi r = resolver.resolve(new DemoAoi("norcal"), store);
        // hand calc: R^2 * dLon * (sin 42 - sin 38) = 170,454.9 km^2
        assertThat(r.areaKm2()).isCloseTo(170_454.9, within(0.5));
        assertThat(r.demoAreaId()).isEqualTo("norcal");
        assertThat(r.countries()).containsExactly(Country.United_States);
        assertThat(r.contains(39.55, -121.45)).isTrue();
        assertThat(r.contains(50.65, -120.35)).isFalse();
    }

    @Test
    void unknownDemoAreaIsBadInput() {
        assertCode(() -> resolver.resolve(new DemoAoi("atlantis"), store), ErrorCode.BAD_INPUT);
    }

    @Test
    void circleUsesCosLatCorrectedDegrees() {
        ResolvedAoi r = resolver.resolve(new CircleAoi(39.5, -121.5, 50), store);
        assertThat(r.areaKm2()).isCloseTo(Math.PI * 50 * 50, within(1.0));
        assertThat(r.contains(39.5, -121.5)).isTrue();
        assertThat(r.contains(39.9, -121.5)).isTrue();   // 0.4 deg N = 44.5 km
        assertThat(r.contains(39.5, -121.0)).isTrue();   // 0.5 deg E at cos(39.5)=0.7716 -> 42.9 km
        assertThat(r.contains(39.5, -120.9)).isFalse();  // 0.6 deg E -> 51.5 km
        assertThat(r.demoAreaId()).isNull();
    }

    @Test
    void circleValidation() {
        assertCode(() -> resolver.resolve(new CircleAoi(95, -121, 10), store), ErrorCode.BAD_INPUT);
        assertCode(() -> resolver.resolve(new CircleAoi(40, -190, 10), store), ErrorCode.BAD_INPUT);
        assertCode(() -> resolver.resolve(new CircleAoi(40, -121, 0), store), ErrorCode.BAD_INPUT);
        assertCode(() -> resolver.resolve(new CircleAoi(40, -121, Double.NaN), store), ErrorCode.BAD_INPUT);
    }

    @Test
    void outsideNorthAmericaIsRejected() {
        assertCode(() -> resolver.resolve(new CircleAoi(53.35, -6.26, 20), store), ErrorCode.AOI_OUTSIDE_NA); // Dublin
        assertCode(() -> resolver.resolve(new CircleAoi(30, -40, 50), store), ErrorCode.AOI_OUTSIDE_NA);      // mid-Atlantic
        assertCode(() -> resolver.resolve(new CircleAoi(-15, -60, 50), store), ErrorCode.AOI_OUTSIDE_NA);     // Bolivia
    }

    @Test
    void coastalCircleIsAccepted() {
        // San Francisco: half the circle is ocean; allowed via the 1 deg coastal tolerance
        ResolvedAoi r = resolver.resolve(new CircleAoi(37.77, -122.42, 40), store);
        assertThat(r.countries()).containsExactly(Country.United_States);
    }

    @Test
    void tooLargeAreaIsRejected() {
        // pi * 600^2 = 1,130,973 km^2 > 1,000,000
        assertCode(() -> resolver.resolve(new CircleAoi(45, -100, 600), store), ErrorCode.AOI_TOO_LARGE);
    }

    @Test
    void polygonInMexicoIsAccepted() {
        ResolvedAoi r = resolver.resolve(polygon(List.of(
                pt(-107.5, 25.5), pt(-106.5, 25.5), pt(-106.5, 26.5), pt(-107.5, 26.5), pt(-107.5, 25.5))), store);
        assertThat(r.countries()).containsExactly(Country.Mexico);
        assertThat(r.contains(26.15, -107.25)).isTrue();
    }

    @Test
    void unclosedRingIsClosedForTheUser() {
        ResolvedAoi r = resolver.resolve(polygon(List.of(
                pt(-107.5, 25.5), pt(-106.5, 25.5), pt(-106.5, 26.5), pt(-107.5, 26.5))), store);
        assertThat(r.contains(26.15, -107.25)).isTrue();
    }

    @Test
    void polygonValidation() {
        // bow-tie (self-intersecting)
        assertCode(() -> resolver.resolve(polygon(List.of(
                pt(-107, 25), pt(-106, 26), pt(-106, 25), pt(-107, 26), pt(-107, 25))), store), ErrorCode.BAD_INPUT);
        // longitude out of range
        assertCode(() -> resolver.resolve(polygon(List.of(
                pt(200, 25), pt(201, 25), pt(201, 26), pt(200, 25))), store), ErrorCode.BAD_INPUT);
        // wrong GeoJSON type
        assertCode(() -> resolver.resolve(new PolygonAoi(new GeoJsonPolygon("MultiPolygon", List.of())), store), ErrorCode.BAD_INPUT);
        // missing geometry
        assertCode(() -> resolver.resolve(new PolygonAoi(null), store), ErrorCode.BAD_INPUT);
        assertCode(() -> resolver.resolve(null, store), ErrorCode.BAD_INPUT);
    }

    private static PolygonAoi polygon(List<List<Double>> ring) {
        return new PolygonAoi(new GeoJsonPolygon("Polygon", List.of(ring)));
    }

    private static List<Double> pt(double lon, double lat) {
        return List.of(lon, lat);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(code));
    }
}
