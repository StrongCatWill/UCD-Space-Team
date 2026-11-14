package ie.ucd.space.firecal.contract.api;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/** GET /api/device/{area_id} (no envelope). last12 = 12 monthly MEC values, oldest first, null = no_data. */
public record DevicePayload(
        String areaId,
        String areaName,
        Instant asOf,
        YearMonth month,
        Double mec,
        Double median,
        Double anomalyPct,
        List<Double> last12,
        DeviceOutlook outlook,
        DeviceOfficial official,
        boolean alert,
        boolean snapshot) {}
