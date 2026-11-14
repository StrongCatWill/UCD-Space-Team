package ie.ucd.space.firecal.api.web;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.data.DataStoreHolder;
import ie.ucd.space.firecal.contract.YearRange;
import ie.ucd.space.firecal.contract.api.Envelope;
import ie.ucd.space.firecal.contract.api.MetaCalibration;
import ie.ucd.space.firecal.contract.api.MetaData;
import ie.ucd.space.firecal.contract.files.Calibration;
import ie.ucd.space.firecal.contract.files.Coverage;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaController {

    private final DataStoreHolder holder;
    private final EnvelopeFactory envelopes;

    public MetaController(DataStoreHolder holder, EnvelopeFactory envelopes) {
        this.holder = holder;
        this.envelopes = envelopes;
    }

    @Operation(summary = "Switch month, series start, calibration skill, coverage and demo areas")
    @GetMapping("/api/meta")
    public Envelope<MetaData> meta() {
        DataStore s = holder.current();
        Calibration c = s.calibration();
        Coverage cov = s.coverage();
        // overlap = years both series have science-quality data
        int overlapFrom = Math.max(cov.modisAqua().from().getYear(), cov.viirsNoaa20().from().getYear());
        int overlapTo = Math.min(cov.modisAqua().spUntil().getYear(), cov.viirsNoaa20().spUntil().getYear());
        var v = c.validation();
        MetaCalibration mc = new MetaCalibration(new YearRange(overlapFrom, overlapTo), c.train(),
                v == null ? null : v.holdout(), v == null ? 0 : v.mape(), v == null ? 0 : v.corr());
        return envelopes.wrap(s, new MetaData(c.switchMonth(), c.seriesStart(), mc, cov, s.demoAreas()));
    }
}
