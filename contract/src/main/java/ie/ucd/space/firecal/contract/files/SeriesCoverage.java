package ie.ucd.space.firecal.contract.files;

import java.time.LocalDate;

/** First detection date of a series and last date of science-quality (SP) data; after sp_until = NRT. */
public record SeriesCoverage(LocalDate from, LocalDate spUntil) {}
