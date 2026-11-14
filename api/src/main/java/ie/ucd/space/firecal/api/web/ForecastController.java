package ie.ucd.space.firecal.api.web;

import ie.ucd.space.firecal.api.calc.AoiQueryFactory;
import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.api.data.DataStoreHolder;
import ie.ucd.space.firecal.api.outlook.ForecastService;
import ie.ucd.space.firecal.contract.api.AoiRequest;
import ie.ucd.space.firecal.contract.api.Envelope;
import ie.ucd.space.firecal.contract.api.ForecastData;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ForecastController {

    private final DataStoreHolder holder;
    private final AoiQueryFactory queries;
    private final ForecastService forecasts;
    private final EnvelopeFactory envelopes;

    public ForecastController(DataStoreHolder holder, AoiQueryFactory queries, ForecastService forecasts, EnvelopeFactory envelopes) {
        this.holder = holder;
        this.queries = queries;
        this.forecasts = forecasts;
        this.envelopes = envelopes;
    }

    @Operation(summary = "Official SPC fire-weather outlook for the AOI and, separately, our experimental next-month estimate")
    @PostMapping("/api/forecast")
    public Envelope<ForecastData> forecast(@RequestBody AoiRequest req) {
        DataStore s = holder.current();
        return envelopes.wrap(s, forecasts.forecast(queries.from(req, s), s));
    }
}
