package ie.ucd.space.firecal.contract.api;

import java.time.Instant;

public record OfficialOutlook(
        String provider,
        String product,
        OutlookLevel level,
        String levelLabel,
        Instant issuedAt,
        Instant validTo,
        String link) {}
