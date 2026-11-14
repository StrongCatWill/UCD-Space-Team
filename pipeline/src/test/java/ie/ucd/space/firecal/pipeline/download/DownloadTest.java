package ie.ucd.space.firecal.pipeline.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ie.ucd.space.firecal.contract.Country;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.SortedSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No network: a fake Fetcher stands in for HttpClient. */
class DownloadTest {

    @Test
    void urlsMatchFirmsLayout() {
        assertEquals("https://firms.modaps.eosdis.nasa.gov/data/country/modis/2003/modis_2003_United_States.csv",
                new CountryFile(FirmsProduct.MODIS, 2003, Country.United_States).url().toString());
        assertEquals("https://firms.modaps.eosdis.nasa.gov/data/country/viirs-jpss1/2018/viirs-jpss1_2018_Canada.csv",
                new CountryFile(FirmsProduct.VIIRS, 2018, Country.Canada).url().toString());
    }

    @Test
    void defaultYearsStartAt2003AndPlanHas93Files() {
        SortedSet<Integer> years = DownloadCommand.parseYears(DownloadCommand.DEFAULT_YEARS);
        assertEquals(2003, years.first());
        assertEquals(2025, years.last());
        List<CountryFile> plan = CountryFile.plan(DownloadCommand.products(null), years, EnumSet.allOf(Country.class));
        // MODIS 23 years x 3 + VIIRS 2018-2025 (8) x 3
        assertEquals(69 + 24, plan.size());
        assertTrue(plan.stream().filter(f -> f.product() == FirmsProduct.VIIRS).allMatch(f -> f.year() >= 2018));
        assertEquals("modis_2003_United_States.csv", plan.get(0).fileName());
    }

    @Test
    void parsesYearListsAndOnly() {
        assertEquals(List.of(2001, 2002, 2010, 2011), new ArrayList<>(DownloadCommand.parseYears("2001-2002, 2010-2011")));
        assertThrows(IllegalArgumentException.class, () -> DownloadCommand.parseYears("2010-2005"));
        assertEquals(EnumSet.of(FirmsProduct.VIIRS), DownloadCommand.products("viirs"));
        assertEquals(EnumSet.of(FirmsProduct.MODIS, FirmsProduct.VIIRS), DownloadCommand.products(null)); // no S-NPP by default
        assertThrows(IllegalArgumentException.class, () -> DownloadCommand.products("goes"));
        List<CountryFile> snpp = CountryFile.plan(DownloadCommand.products("snpp"),
                DownloadCommand.parseYears("2010-2013"), EnumSet.of(Country.Mexico));
        assertEquals(List.of("viirs-snpp_2012_Mexico.csv", "viirs-snpp_2013_Mexico.csv"),
                snpp.stream().map(CountryFile::fileName).toList());
    }

    @Test
    void skipsExistingFetchesMissingAndCountsFailures(@TempDir Path dir) throws IOException {
        CountryFile existing = new CountryFile(FirmsProduct.MODIS, 2003, Country.Canada);
        CountryFile missing = new CountryFile(FirmsProduct.MODIS, 2003, Country.Mexico);
        CountryFile notFound = new CountryFile(FirmsProduct.VIIRS, 2018, Country.Mexico);
        Files.writeString(dir.resolve(existing.fileName()), "keep");
        Files.writeString(dir.resolve(missing.fileName() + ".part"), "stale leftover from interrupted run");

        List<URI> fetched = new ArrayList<>();
        Downloader.Fetcher fake = (url, target) -> {
            fetched.add(url);
            if (url.toString().contains("viirs")) {
                Files.writeString(target, "<html>404</html>");
                return 404;
            }
            assertFalse(Files.exists(target), "stale .part must be removed before fetching");
            Files.writeString(target, "latitude,longitude\n1,2\n");
            return 200;
        };
        ByteArrayOutputStream logBuf = new ByteArrayOutputStream();
        Downloader.Summary s = new Downloader(fake, new PrintStream(logBuf, true, StandardCharsets.UTF_8))
                .run(List.of(existing, missing, notFound), dir);

        assertEquals(new Downloader.Summary(1, 1, 1, 23), s);
        assertEquals(List.of(missing.url(), notFound.url()), fetched); // existing file never requested
        assertEquals("keep", Files.readString(dir.resolve(existing.fileName())));
        assertTrue(Files.exists(dir.resolve(missing.fileName())));
        assertFalse(Files.exists(dir.resolve(notFound.fileName())));
        try (var listing = Files.list(dir)) {
            assertTrue(listing.noneMatch(p -> p.toString().endsWith(".part")), "no .part files left behind");
        }
        assertTrue(logBuf.toString(StandardCharsets.UTF_8).contains("HTTP 404"));
    }

    @Test
    void secondRunIsNoOp(@TempDir Path dir) throws IOException {
        List<CountryFile> plan = CountryFile.plan(EnumSet.of(FirmsProduct.VIIRS),
                DownloadCommand.parseYears("2018-2019"), EnumSet.allOf(Country.class));
        for (CountryFile f : plan) Files.writeString(dir.resolve(f.fileName()), "x");
        Downloader.Fetcher mustNotCall = (url, target) -> { throw new AssertionError("fetched " + url); };
        Downloader.Summary s = new Downloader(mustNotCall, new PrintStream(new ByteArrayOutputStream())).run(plan, dir);
        assertEquals(new Downloader.Summary(0, 6, 0, 0), s);
    }
}
