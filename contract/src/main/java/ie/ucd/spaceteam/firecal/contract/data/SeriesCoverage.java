package ie.ucd.spaceteam.firecal.contract.data;

import java.time.LocalDate;
import java.time.YearMonth;

/** from = first month in the series; sp_until = last day covered by the SP product (NRT after it). */
public record SeriesCoverage(YearMonth from, LocalDate spUntil) {}
