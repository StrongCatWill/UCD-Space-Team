package ie.ucd.space.firecal.pipeline;

import ie.ucd.space.firecal.pipeline.download.DownloadCommand;
import ie.ucd.space.firecal.pipeline.model.ModelTableCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** D1 batch pipeline: FIRMS CSVs -> out/*.json, out/hotspots/*.csv. Subcommands are added per task. */
@Command(name = "pipeline", mixinStandardHelpOptions = true, version = "pipeline 0.1.0",
        subcommands = {DownloadCommand.class, ModelTableCommand.class},
        description = "Harmonized Fire Activity Calendar - data pipeline (D1).")
public class Main implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
