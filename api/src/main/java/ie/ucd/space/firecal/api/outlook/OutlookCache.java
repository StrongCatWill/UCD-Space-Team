package ie.ucd.space.firecal.api.outlook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Last good SPC snapshot; refreshed at most every fire.spc.refresh (30 min); failures keep the old copy. */
public class OutlookCache {

    public record Snapshot(Instant fetchedAt, List<SpcFeature> features) {}

    private static final Logger log = LoggerFactory.getLogger(OutlookCache.class);
    private final OutlookSource source;
    private final boolean enabled;
    private final AtomicReference<Snapshot> last = new AtomicReference<>();
    private volatile String lastError;

    public OutlookCache(OutlookSource source, boolean enabled) {
        this.source = source;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "${fire.spc.refresh:PT30M}")
    public void refresh() {
        if (!enabled) return;
        try {
            List<SpcFeature> features = source.fetchAll();
            last.set(new Snapshot(Instant.now(), List.copyOf(features)));
            lastError = null;
            log.info("SPC outlook refreshed: {} active polygons", features.size());
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("SPC outlook refresh failed, keeping last good copy ({}): {}",
                    last.get() == null ? "none" : last.get().fetchedAt(), e.getMessage());
        }
    }

    public Optional<Snapshot> current() {
        return Optional.ofNullable(last.get());
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }
}
