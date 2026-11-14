package ie.ucd.space.firecal.contract.files;

import ie.ucd.space.firecal.contract.YearRange;
import java.util.Map;

/** Hold-out validation. by_country is keyed by Country name (United_States, Canada, Mexico). */
public record CalibrationValidation(
        YearRange holdout,
        double mape,
        double corr,
        Map<String, ValidationScore> byCountry) {}
