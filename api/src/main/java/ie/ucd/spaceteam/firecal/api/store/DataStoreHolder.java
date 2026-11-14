package ie.ucd.spaceteam.firecal.api.store;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Holds the current {@link DataStore}; reload swaps atomically and keeps the old store if loading fails. */
@Component
public class DataStoreHolder {

    private static final Logger log = LoggerFactory.getLogger(DataStoreHolder.class);

    private final DataStoreLoader loader;
    private final AtomicReference<DataStore> current = new AtomicReference<>();

    public DataStoreHolder(DataStoreLoader loader) {
        this.loader = loader;
        DataStore first = loader.load(); // start-up failure = app does not start (fail loudly)
        current.set(first);
        logLoaded(first);
    }

    public DataStore get() {
        return current.get();
    }

    /** @return the new store, or throws (old store stays active). */
    public synchronized DataStore reload() {
        try {
            DataStore next = loader.load();
            current.set(next);
            logLoaded(next);
            return next;
        } catch (RuntimeException e) {
            log.error("reload failed, keeping store loaded at {}: {}", current.get().loadedAt(), e.getMessage());
            throw e;
        }
    }

    private static void logLoaded(DataStore s) {
        log.info("data store loaded from {}: version={}, demo areas={}, snapshot={}",
                s.location(), s.harmonizationVersion(), s.demoFiles().keySet(), s.snapshot().areaId());
    }
}
