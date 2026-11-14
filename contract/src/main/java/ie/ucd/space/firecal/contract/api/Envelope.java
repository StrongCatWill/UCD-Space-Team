package ie.ucd.space.firecal.contract.api;

import java.time.Instant;
import java.util.List;

/** Wrapper on every JSON response except /api/device. */
public record Envelope<T>(Instant asOf, String harmonizationVersion, List<DataSource> sources, T data) {}
