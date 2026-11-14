package ie.ucd.space.firecal.pipeline.download;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

/**
 * Downloads country files into an archive directory. Existing files are skipped; each download goes to
 * {@code <name>.part} and is renamed only on success, so an interrupted run never leaves a half file.
 */
public final class Downloader {

    /** Fetches {@code url} into {@code target}. Returns the HTTP status; the target is only meaningful on 200. */
    @FunctionalInterface
    public interface Fetcher {
        int fetch(URI url, Path target) throws IOException, InterruptedException;
    }

    public record Summary(int downloaded, int skipped, int failed, long bytes) {}

    private final Fetcher fetcher;
    private final PrintStream log;

    public Downloader(Fetcher fetcher, PrintStream log) {
        this.fetcher = fetcher;
        this.log = log;
    }

    public Summary run(List<CountryFile> files, Path archiveDir) throws IOException {
        Files.createDirectories(archiveDir);
        int downloaded = 0, skipped = 0, failed = 0;
        long bytes = 0;
        for (CountryFile f : files) {
            Path target = archiveDir.resolve(f.fileName());
            if (Files.isRegularFile(target)) {
                skipped++;
                continue;
            }
            Path part = archiveDir.resolve(f.fileName() + ".part");
            try {
                Files.deleteIfExists(part); // leftover from an interrupted run
                int status = fetcher.fetch(f.url(), part);
                if (status != 200) {
                    Files.deleteIfExists(part);
                    failed++;
                    log.println("FAIL  " + f.fileName() + "  HTTP " + status + "  " + f.url());
                    continue;
                }
                long size = Files.size(part);
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                downloaded++;
                bytes += size;
                log.println("OK    " + f.fileName() + "  " + size + " bytes");
            } catch (IOException e) {
                Files.deleteIfExists(part);
                failed++;
                log.println("FAIL  " + f.fileName() + "  " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Files.deleteIfExists(part);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        return new Summary(downloaded, skipped, failed, bytes);
    }

    /** Real fetcher: streams the body straight to disk on 200, discards it otherwise. */
    public static Fetcher http() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return (url, target) -> {
            HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(15)).GET().build();
            HttpResponse<Path> resp = client.send(req, info -> info.statusCode() == 200
                    ? HttpResponse.BodySubscribers.ofFile(target,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                    : HttpResponse.BodySubscribers.replacing(target)); // error body discarded, nothing written
            return resp.statusCode();
        };
    }
}
