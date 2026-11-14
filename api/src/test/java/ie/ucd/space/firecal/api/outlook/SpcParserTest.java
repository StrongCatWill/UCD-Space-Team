package ie.ucd.space.firecal.api.outlook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ie.ucd.space.firecal.contract.api.OutlookLevel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpcParserTest {

    private static JsonNode fixture() throws Exception {
        return new ObjectMapper().readTree(SpcParserTest.class.getResourceAsStream("/spc/layer1_day1.geojson"));
    }

    @Test
    void parsesDnLevelsSkipsEmptyGeometryAndReadsTimes() throws Exception {
        List<SpcFeature> fs = SpcParser.parse(1, fixture());
        assertThat(fs).extracting(SpcFeature::level)
                .containsExactly(OutlookLevel.ELEVATED, OutlookLevel.CRITICAL, OutlookLevel.EXTREMELY_CRITICAL);
        SpcFeature critical = fs.get(1);
        assertThat(critical.product()).isEqualTo("Day 1 Fire Weather Outlook");
        assertThat(critical.label()).isEqualTo("Critical");
        assertThat(critical.issuedAt()).isEqualTo(Instant.ofEpochMilli(1794674725000L));
        assertThat(critical.validTo()).isEqualTo(Instant.parse("2026-11-15T12:00:00Z"));
    }

    @Test
    void dryThunderstormLayerMapsToElevatedAndCritical() throws Exception {
        JsonNode n = new ObjectMapper().readTree("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-120,40],[-119,40],[-119,41],[-120,40]]]},
                  "properties":{"dn":5,"valid":"202611151200","expire":"202611161200","idp_filedate":1794639920000}},
                 {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-120,40],[-119,40],[-119,41],[-120,40]]]},
                  "properties":{"dn":8,"valid":"202611151200","expire":"202611161200","idp_filedate":1794639920000}}]}""");
        List<SpcFeature> fs = SpcParser.parse(5, n);
        assertThat(fs).extracting(SpcFeature::level).containsExactly(OutlookLevel.ELEVATED, OutlookLevel.CRITICAL);
        assertThat(fs).extracting(SpcFeature::label).containsExactly("Isolated Dry Thunderstorms", "Scattered Dry Thunderstorms");
        assertThat(fs.get(0).product()).isEqualTo("Day 2 Dry Thunderstorm Outlook");
    }

    @Test
    void failsLoudlyOnUnknownDnOrLayerOrArcgisError() throws Exception {
        JsonNode weird = new ObjectMapper().readTree("""
                {"type":"FeatureCollection","features":[{"type":"Feature",
                 "geometry":{"type":"Polygon","coordinates":[[[-120,40],[-119,40],[-119,41],[-120,40]]]},
                 "properties":{"dn":7,"valid":"202611151200","expire":"202611161200"}}]}""");
        assertThatThrownBy(() -> SpcParser.parse(1, weird)).hasMessageContaining("dn=7");
        assertThatThrownBy(() -> SpcParser.parse(7, fixture())).hasMessageContaining("layer 7");
        JsonNode err = new ObjectMapper().readTree("{\"error\":{\"code\":400,\"message\":\"Invalid query\"}}");
        assertThatThrownBy(() -> SpcParser.parse(1, err)).hasMessageContaining("Invalid query");
    }
}
