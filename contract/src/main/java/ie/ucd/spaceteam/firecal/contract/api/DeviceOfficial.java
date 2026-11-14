package ie.ucd.spaceteam.firecal.contract.api;

import java.time.Instant;

public record DeviceOfficial(String level, String source, Instant issued) {}
