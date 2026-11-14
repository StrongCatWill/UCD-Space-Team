package org.spaceapps.fire.forecast;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.spaceapps.fire.dto.Bbox;
import org.spaceapps.fire.dto.CellForecast;
import org.spaceapps.fire.dto.ForecastFile;
import org.spaceapps.fire.dto.ForecastRequest;
import org.spaceapps.fire.dto.ForecastResponse;
import org.spaceapps.fire.dto.RegionDay;
import tools.jackson.databind.json.JsonMapper;

/**
 * Precomputed forecasts (notebook step 25). Used when the Python service is unreachable,
 * so the demo never shows an empty map. It only knows one asOf date.
 */
public class FallbackForecastStore {

    public static final String SOURCE = "fallback-precomputed";
    private final ForecastFile file;

    public FallbackForecastStore(InputStream json, JsonMapper mapper) {
        this.file = mapper.readValue(json, ForecastFile.class);
    }

    public static FallbackForecastStore load(org.springframework.core.io.Resource resource, JsonMapper mapper) {
        try (InputStream in = resource.getInputStream()) {
            return new FallbackForecastStore(in, mapper);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fallback forecasts " + resource, e);
        }
    }

    public String asOf() {
        return file.asOf();
    }

    public ForecastResponse forecast(ForecastRequest request) {
        Bbox box = Bbox.of(request.bbox());
        int days = request.days() == null ? 7 : request.days();
        Map<String, List<CellForecast>> byDate = new TreeMap<>();
        for (CellForecast c : file.items()) {
            if (box.contains(c.lat(), c.lon())) {
                byDate.computeIfAbsent(c.date(), k -> new ArrayList<>()).add(c);
            }
        }
        List<RegionDay> region = new ArrayList<>();
        List<CellForecast> items = new ArrayList<>();
        int cells = 0;
        for (var e : byDate.entrySet()) {
            if (region.size() == days) break;
            List<CellForecast> list = e.getValue();
            cells = list.size();
            double sumP = 0, sumC = 0;
            for (CellForecast c : list) {
                sumP += c.p();
                sumC += c.pClim();
            }
            double meanP = sumP / list.size(), meanC = sumC / list.size();
            region.add(new RegionDay(e.getKey(), round(sumP, 2), round(meanP, 4), round(meanC, 4),
                    round(meanP / Math.max(meanC, 1e-4), 2), null));
            items.addAll(list);
        }
        return new ForecastResponse(file.modelVersion(), file.asOf(), cells, region, items, SOURCE);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
