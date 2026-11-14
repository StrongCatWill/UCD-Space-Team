package ie.ucd.space.firecal.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ie.ucd.space.firecal.api.ApiTestBase;
import ie.ucd.space.firecal.contract.files.HotspotCsv;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

class HotspotsCsvApiTest extends ApiTestBase {

    // norcal bbox [-124.5, 38.0, -120.0, 42.0]
    static final String NORCAL_2025 = "{\"aoi\":{\"type\":\"demo\",\"area_id\":\"norcal\"},\"start\":\"2025-01\",\"end\":\"2025-12\"%s}";

    private List<CSVRecord> rows(String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/hotspots.csv").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("hotspots_2025-01_2025-12.csv");
        String csv = r.getResponse().getContentAsString();
        assertThat(csv).startsWith("# Data: NASA FIRMS");
        var parser = CSVFormat.DEFAULT.builder().setCommentMarker('#').setHeader().setSkipHeaderRecord(true).get()
                .parse(new StringReader(csv));
        List<String> expectedHeader = new ArrayList<>(HotspotCsv.COLUMNS);
        expectedHeader.add("harmonization_version");
        assertThat(parser.getHeaderNames()).containsExactlyElementsOf(expectedHeader);
        return parser.getRecords();
    }

    @Test
    void defaultFiltersKeepAoiPeriodNominalPlusAndDropStatic() throws Exception {
        List<CSVRecord> rows = rows(NORCAL_2025.formatted(""));
        assertThat(rows).isNotEmpty();
        for (CSVRecord r : rows) {
            double lat = Double.parseDouble(r.get("latitude")), lon = Double.parseDouble(r.get("longitude"));
            assertThat(lat).isBetween(38.0, 42.0);
            assertThat(lon).isBetween(-124.5, -120.0);
            assertThat(LocalDate.parse(r.get("acq_date")).getYear()).isEqualTo(2025);
            assertThat(r.get("confidence_class")).isIn("nominal", "high");
            assertThat(r.get("type")).isNotIn("1", "2");
            assertThat(r.get("harmonization_version")).isEqualTo("mock-h1.0");
            assertThat(r.get("parent")).isEqualTo("United_States");
        }
        // sorted by acq_date, acq_time_utc
        for (int i = 1; i < rows.size(); i++) {
            String a = rows.get(i - 1).get("acq_date") + rows.get(i - 1).get("acq_time_utc");
            String b = rows.get(i).get("acq_date") + rows.get(i).get("acq_time_utc");
            assertThat(a.compareTo(b)).isLessThanOrEqualTo(0);
        }
    }

    @Test
    void lowConfidenceAndStaticWidenTheResult() throws Exception {
        int strict = rows(NORCAL_2025.formatted("")).size();
        List<CSVRecord> wide = rows(NORCAL_2025.formatted(",\"min_conf\":\"low\",\"include_static\":true"));
        assertThat(wide.size()).isGreaterThan(strict);
        assertThat(wide).anyMatch(r -> r.get("confidence_class").equals("low"));
        assertThat(wide).anyMatch(r -> r.get("type").equals("2"));
        int high = rows(NORCAL_2025.formatted(",\"min_conf\":\"high\"")).size();
        assertThat(high).isLessThan(strict);
    }

    @Test
    void outsideNaIsJsonError() throws Exception {
        mvc.perform(post("/api/hotspots.csv").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aoi\":{\"type\":\"circle\",\"lat\":53.35,\"lon\":-6.26,\"radius_km\":10}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AOI_OUTSIDE_NA"));
    }

    @Nested
    @TestPropertySource(properties = "fire.hotspot-row-limit=5")
    class RowLimit extends ApiTestBase {
        @Test
        void moreThanLimitRowsIsAoiTooLarge() throws Exception {
            mvc.perform(post("/api/hotspots.csv").contentType(MediaType.APPLICATION_JSON).content(NORCAL_2025.formatted("")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AOI_TOO_LARGE"));
        }
    }
}
