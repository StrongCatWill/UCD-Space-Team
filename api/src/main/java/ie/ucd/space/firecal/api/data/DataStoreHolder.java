package ie.ucd.space.firecal.api.data;

import ie.ucd.space.firecal.api.config.FireProperties;
import jakarta.annotation.PostConstruct;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Holds the current {@link DataStore}; reload swaps atomically and keeps the old store on failure. */
@Component
public class DataStoreHolder {

    public record ReloadResult(boolean reloaded, String message, int cellMonths, YearMonth maxYm) {}

    private static final Logger log = LoggerFactory.getLogger(DataStoreHolder.class);
    private final DataStoreLoader loader;
    private final String dataDir;
    private final String demoAreas;
    private final AtomicReference<DataStore> current = new AtomicReference<>();

    @Autowired
    public DataStoreHolder(DataStoreLoader loader, FireProperties props) {
        this(loader, props.dataDir(), props.demoAreas());
    }

    public DataStoreHolder(DataStoreLoader loader, String dataDir, String demoAreas) {
        this.loader = loader;
        this.dataDir = dataDir;
        this.demoAreas = demoAreas;
    }

    /** Start-up load: fails the application loudly if the files are missing or broken. */
    @PostConstruct
    public void init() {
        current.set(loader.load(dataDir, demoAreas));
    }

    public DataStore current() {
        return current.get();
    }

    /** @param overrideDir null = configured fire.data-dir */
    public synchronized ReloadResult reload(String overrideDir) {
        DataStore old = current.get();
        try {
            DataStore fresh = loader.load(overrideDir == null ? dataDir : overrideDir, demoAreas);
            current.set(fresh);
            return new ReloadResult(true, "reloaded from " + fresh.dataDir(), fresh.cellMonthCount(), fresh.maxYm());
        } catch (RuntimeException e) {
            log.warn("Reload failed, keeping previous data: {}", e.getMessage());
            return new ReloadResult(false, "reload failed, previous data kept: " + e.getMessage(),
                    old.cellMonthCount(), old.maxYm());
        }
    }
}
