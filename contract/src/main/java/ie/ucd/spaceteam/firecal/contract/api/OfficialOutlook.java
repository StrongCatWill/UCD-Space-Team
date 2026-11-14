package ie.ucd.spaceteam.firecal.contract.api;

import java.time.Instant;

/** One official product (e.g. provider "NOAA SPC", product "Fire Weather Outlook Day 1"). Never blended with ours. */
public record OfficialOutlook(
        String provider,
        String product,
        String level,
        String levelLabel,
        Instant issuedAt,
        Instant validTo,
        String link) {}
