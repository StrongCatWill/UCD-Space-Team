package ie.ucd.space.firecal.api.web;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.contract.DataLevel;
import ie.ucd.space.firecal.contract.api.DataSource;
import ie.ucd.space.firecal.contract.api.Envelope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Builds the {as_of, harmonization_version, sources, data} envelope from the store that produced the data. */
@Component
public class EnvelopeFactory {

    public static final String MODIS = "NASA FIRMS MODIS C6.1";
    public static final String VIIRS = "NASA FIRMS VIIRS NOAA-20 C2";

    public <T> Envelope<T> wrap(DataStore store, T data) {
        return new Envelope<>(store.asOf(), store.calibration().harmonizationVersion(), sources(store), data);
    }

    public static List<DataSource> sources(DataStore store) {
        List<DataSource> s = new ArrayList<>();
        s.add(new DataSource(MODIS, DataLevel.SP));
        s.add(new DataSource(VIIRS, DataLevel.SP));
        if (store.anyProvisional()) s.add(new DataSource(VIIRS, DataLevel.NRT));
        return s;
    }
}
