package ie.ucd.space.firecal.api;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.data.DataStoreLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Builds a tiny out/ directory for hand-calculated tests. One cell "395_-1215" (39.55, -121.45, United_States,
 * region B_35_-125, a = 0.3 for all months). switch 2025-01, Aqua SP until 2026-08-31, NOAA-20 SP until 2026-06-30.
 */
public final class TinyOut {
    private final List<String> rows = new ArrayList<>();

    public TinyOut month(String ym, double mec) {
        boolean viirs = ym.compareTo("2025-01") >= 0;
        boolean provisional = viirs && ym.compareTo("2026-06") > 0;
        rows.add(String.format(Locale.ROOT,
                "{\"cell_id\":\"395_-1215\",\"lat\":39.55,\"lon\":-121.45,\"parent\":\"United_States\",\"ym\":\"%s\","
                        + "\"mec\":%s,\"mec_with_static\":%s,\"raw_count\":%d,\"raw_sensor\":\"%s\",\"source_flag\":\"%s\",\"provisional\":%s}",
                ym, mec, mec, (int) Math.round(viirs ? mec / 0.3 : mec), viirs ? "VIIRS_NOAA20" : "MODIS_AQUA",
                viirs ? "VIIRS_CAL" : "MODIS", provisional));
        return this;
    }

    public DataStore write(Path dir, String demoAreasJson) throws Exception {
        StringBuilder regions = new StringBuilder();
        for (int m = 1; m <= 12; m++) {
            if (m > 1) regions.append(',');
            regions.append("{\"region_id\":\"B_35_-125\",\"parent\":\"United_States\",\"month\":").append(m)
                    .append(",\"a\":0.3,\"a_p25\":0.24,\"a_p75\":0.39,\"n_years\":6,\"level\":\"region\"}");
        }
        Files.writeString(dir.resolve("calibration.json"), """
                {"harmonization_version":"test-h1","generated_at":"2026-11-14T08:00:00Z","train":{"from":2018,"to":2023},
                 "switch_month":"2025-01","series_start":"2003-01","regions":[%s],"country":[],"north_america":[],
                 "validation":{"holdout":{"from":2024,"to":2025},"mape":10.0,"corr":0.9,"by_country":{}}}""".formatted(regions));
        Files.writeString(dir.resolve("coverage.json"), """
                {"MODIS_AQUA":{"from":"2002-07-04","sp_until":"2026-08-31"},"VIIRS_NOAA20":{"from":"2018-01-01","sp_until":"2026-06-30"}}""");
        Files.writeString(dir.resolve("cell_month.json"), "[" + String.join(",", rows) + "]");
        Path areas = dir.resolve("demo_areas.json");
        Files.writeString(areas, demoAreasJson);
        return new DataStoreLoader(new DefaultResourceLoader()).load(dir.toUri().toString(), areas.toUri().toString());
    }

    public static final String TINY_AREAS = """
            [{"area_id":"tiny","name":"Tiny","country":"United_States","bbox":[-121.5,39.5,-121.4,39.6]}]""";
}
