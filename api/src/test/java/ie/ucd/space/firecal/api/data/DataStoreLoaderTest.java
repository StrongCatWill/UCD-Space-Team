package ie.ucd.space.firecal.api.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class DataStoreLoaderTest {

    private final DataStoreLoader loader = new DataStoreLoader(new DefaultResourceLoader());

    @Test
    void loadsMockFilesIntoIndexedStore() {
        DataStore store = loader.load("classpath:mock/", "classpath:config/demo_areas.json");

        assertThat(store.calibration().harmonizationVersion()).isEqualTo("mock-h1.0");
        assertThat(store.calibration().switchMonth()).isEqualTo(YearMonth.of(2025, 1));
        assertThat(store.cells()).hasSize(14);
        assertThat(store.cellMonthCount()).isEqualTo(2844);
        assertThat(store.maxYm()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(store.demoAreas()).extracting(a -> a.areaId()).containsExactly("norcal", "bc_interior", "mx_sierra");
        assertThat(store.snapshot()).isNotNull();
        assertThat(store.monthsOf("395_-1215")).isNotEmpty()
                .allSatisfy(cm -> assertThat(cm.cellId()).isEqualTo("395_-1215"));
    }

    @Test
    void failsLoudlyWhenRequiredFileMissing(@TempDir Path dir) {
        assertThatThrownBy(() -> loader.load(dir.toUri().toString(), "classpath:config/demo_areas.json"))
                .isInstanceOf(DataLoadException.class)
                .hasMessageContaining("calibration.json");
    }

    @Test
    void reloadKeepsOldStoreWhenNewFilesAreBroken(@TempDir Path dir) throws Exception {
        DataStoreHolder holder = new DataStoreHolder(loader, "classpath:mock/", "classpath:config/demo_areas.json");
        holder.init();
        DataStore before = holder.current();

        Files.writeString(dir.resolve("calibration.json"), "{ not json");
        DataStoreHolder.ReloadResult result = holder.reload(dir.toUri().toString());

        assertThat(result.reloaded()).isFalse();
        assertThat(result.message()).contains("calibration.json");
        assertThat(holder.current()).isSameAs(before);
    }

    @Test
    void reloadSwapsAtomicallyOnSuccess() {
        DataStoreHolder holder = new DataStoreHolder(loader, "classpath:mock/", "classpath:config/demo_areas.json");
        holder.init();
        DataStore before = holder.current();

        DataStoreHolder.ReloadResult result = holder.reload(null);

        assertThat(result.reloaded()).isTrue();
        assertThat(result.cellMonths()).isEqualTo(2844);
        assertThat(holder.current()).isNotSameAs(before);
    }
}
