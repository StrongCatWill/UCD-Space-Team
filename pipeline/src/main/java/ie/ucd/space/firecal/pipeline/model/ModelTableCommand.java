package ie.ucd.space.firecal.pipeline.model;

import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.contract.FireJson;
import ie.ucd.space.firecal.pipeline.download.CountryFile;
import ie.ucd.space.firecal.pipeline.download.FirmsProduct;
import ie.ucd.space.firecal.pipeline.ingest.FirmsCsv;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code pipeline modeltable}: archive CSVs -> out/model/harmonised_cell_month.csv (+ .meta.json) for models/.
 * Separate from the calendar contract files (cell_month.json etc.).
 */
@Command(name = "modeltable", mixinStandardHelpOptions = true,
        description = "Build the dense 0.5 deg cell x month table for models/ (MODIS Terra/Aqua, VIIRS S-NPP, "
                + "VIIRS NOAA-20) from the archive CSVs.")
public class ModelTableCommand implements Callable<Integer> {

    @Option(names = "--archive-dir", defaultValue = "archive", description = "Default: ${DEFAULT-VALUE}")
    Path archiveDir;

    @Option(names = "--out", defaultValue = "out/model/harmonised_cell_month.csv", description = "Default: ${DEFAULT-VALUE}")
    Path out;

    @Option(names = "--from", defaultValue = "2002", description = "First year. Default: ${DEFAULT-VALUE}")
    int fromYear;

    @Option(names = "--to", defaultValue = "2025", description = "Last year (complete SP years only). Default: ${DEFAULT-VALUE}")
    int toYear;

    @Option(names = "--min-conf", defaultValue = "nominal", description = "low | nominal | high. Default: ${DEFAULT-VALUE}")
    ConfidenceClass minConf;

    /** Sidecar describing how the table was built (deterministic: no timestamps). */
    public record Meta(List<String> columns, double cellDeg, int fromYear, int toYear, ConfidenceClass minConf,
                       List<Integer> keptTypes, int viirsSnppFrom, int viirsNoaa20From, String landCover,
                       String region, long cells, long rows, ModelTable.Stats stats, List<Input> inputs) {}

    public record Input(String file, long rows) {}

    @Override
    public Integer call() throws Exception {
        List<CountryFile> files = inputs(fromYear, toYear);
        List<String> missing = files.stream().map(CountryFile::fileName)
                .filter(n -> !Files.isRegularFile(archiveDir.resolve(n))).toList();
        if (!missing.isEmpty()) {
            System.err.println("modeltable: " + missing.size() + " input files missing in " + archiveDir + ":");
            missing.forEach(m -> System.err.println("  " + m));
            System.err.println("fetch them with: java -jar pipeline/target/pipeline.jar download --archive-dir "
                    + archiveDir + " [--only snpp --years 2012-2025]");
            return 2;
        }

        ModelTable table = new ModelTable(fromYear, toYear, minConf);
        List<Input> read = new ArrayList<>();
        long t0 = System.nanoTime();
        for (CountryFile f : files) {
            long n = FirmsCsv.read(archiveDir.resolve(f.fileName()), f.product(), f.country(), table::accept);
            read.add(new Input(f.fileName(), n));
            System.out.printf(Locale.ROOT, "read  %-36s %,12d rows%n", f.fileName(), n);
        }

        Files.createDirectories(out.toAbsolutePath().getParent());
        Path part = out.resolveSibling(out.getFileName() + ".part");
        long rows;
        try (Writer w = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
            rows = table.write(w);
        }
        Files.move(part, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        ModelTable.Stats s = table.stats();
        Meta meta = new Meta(ModelTable.COLUMNS, ModelTable.CELL_DEG, fromYear, toYear, minConf, List.of(0),
                ModelTable.SNPP_FROM, ModelTable.NOAA20_FROM,
                "placeholder '" + ModelTable.LAND_COVER_PLACEHOLDER + "' (no land-cover source yet)",
                "5 deg block of the cell centre (B_<lat5>_<lon5>, as calibration.json region_id); country in 'country'",
                table.cellCount(), rows, s, read);
        Path metaPath = out.resolveSibling(out.getFileName().toString().replaceFirst("\\.csv$", "") + ".meta.json");
        FireJson.mapper().writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), meta);

        System.out.printf(Locale.ROOT, "modeltable: detections seen=%,d kept=%,d (dropped: confidence=%,d type=%,d year=%,d)%n",
                s.seen(), s.kept(), s.droppedConfidence(), s.droppedType(), s.droppedYear());
        System.out.printf(Locale.ROOT, "modeltable: cells=%,d rows=%,d (%d-%d, %d months) -> %s  [%.0f s]%n",
                table.cellCount(), rows, fromYear, toYear, (toYear - fromYear + 1) * 12,
                out.toAbsolutePath().normalize(), (System.nanoTime() - t0) / 1e9);
        return 0;
    }

    /** MODIS from..to, S-NPP from max(from, 2012), NOAA-20 from max(from, 2018); all three countries. */
    static List<CountryFile> inputs(int fromYear, int toYear) {
        TreeSet<Integer> years = new TreeSet<>();
        IntStream.rangeClosed(fromYear, toYear).forEach(years::add);
        return CountryFile.plan(EnumSet.allOf(FirmsProduct.class), years, EnumSet.allOf(Country.class));
    }
}
