package ie.ucd.spaceteam.firecal.pipeline;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** D1 batch CLI. Subcommands (download, ingest, calibrate, harmonize, fetch2026, demo, all) are added by D1. */
@Command(name = "pipeline", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Builds out/ from FIRMS MODIS + VIIRS NOAA-20 detections.")
public class Main implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
