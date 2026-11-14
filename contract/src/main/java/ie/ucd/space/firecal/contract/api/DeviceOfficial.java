package ie.ucd.space.firecal.contract.api;

import java.time.Instant;

public record DeviceOfficial(OutlookLevel level, String source, Instant issued) {}
