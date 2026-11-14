package ie.ucd.space.firecal.pipeline.download;

import ie.ucd.space.firecal.contract.Country;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code pipeline download}: FIRMS country yearly CSVs -> archive/. No key needed. */
@Command(name = "download", mixinStandardHelpOptions = true,
        description = "Download FIRMS country yearly CSVs (MODIS, VIIRS NOAA-20) into the archive dir. "
                + "Existing files are skipped.")
public class DownloadCommand implements Callable<Integer> {

    /** series_start = 2003-01. Earlier years (Terra 2000-2002) only via --years. */
    public static final String DEFAULT_YEARS = "2003-2025";

    @Option(names = "--archive-dir", defaultValue = "archive", description = "Default: ${DEFAULT-VALUE}")
    Path archiveDir;

    @Option(names = "--years", defaultValue = DEFAULT_YEARS,
            description = "Years, e.g. 2003-2025 or 2001,2002,2010-2012. Default: ${DEFAULT-VALUE}")
    String years;

    @Option(names = "--countries", split = ",",
            description = "Comma list of ${COMPLETION-CANDIDATES}. Default: all")
    List<Country> countries;

    @Option(names = "--only", description = "modis | viirs | snpp. Default: modis + viirs (NOAA-20); "
            + "snpp (S-NPP, models/ only, from 2012) is fetched only when asked for")
    String only;

    @Override
    public Integer call() throws Exception {
        List<CountryFile> files = CountryFile.plan(products(only), parseYears(years),
                countries == null || countries.isEmpty() ? EnumSet.allOf(Country.class) : EnumSet.copyOf(countries));
        System.out.println("download: " + files.size() + " files planned -> " + archiveDir.toAbsolutePath().normalize());
        Downloader.Summary s = new Downloader(Downloader.http(), System.out).run(files, archiveDir);
        System.out.printf(Locale.ROOT, "download: downloaded=%d skipped=%d failed=%d bytes=%d (%.1f MB)%n",
                s.downloaded(), s.skipped(), s.failed(), s.bytes(), s.bytes() / 1_000_000.0);
        return s.failed() == 0 ? 0 : 1;
    }

    static EnumSet<FirmsProduct> products(String only) {
        if (only == null) return EnumSet.of(FirmsProduct.MODIS, FirmsProduct.VIIRS);
        return switch (only.toLowerCase(Locale.ROOT)) {
            case "modis" -> EnumSet.of(FirmsProduct.MODIS);
            case "viirs" -> EnumSet.of(FirmsProduct.VIIRS);
            case "snpp" -> EnumSet.of(FirmsProduct.SNPP);
            default -> throw new IllegalArgumentException("--only must be modis, viirs or snpp, got: " + only);
        };
    }

    /** "2003-2025" / "2001,2002,2010-2012" -> sorted set of years. */
    static SortedSet<Integer> parseYears(String spec) {
        SortedSet<Integer> out = new TreeSet<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] ab = p.split("-");
            if (ab.length > 2) throw new IllegalArgumentException("bad --years element: " + p);
            int from = Integer.parseInt(ab[0].trim());
            int to = ab.length == 2 ? Integer.parseInt(ab[1].trim()) : from;
            if (from > to || from < 2000 || to > 2100) throw new IllegalArgumentException("bad --years range: " + p);
            for (int y = from; y <= to; y++) out.add(y);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("--years is empty");
        return out;
    }
}
