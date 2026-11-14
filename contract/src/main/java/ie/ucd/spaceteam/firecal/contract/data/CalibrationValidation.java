package ie.ucd.spaceteam.firecal.contract.data;

import java.util.Map;

/** Hold-out check of MEC vs Aqua. by_country is keyed by Country JSON name (United_States, Canada, Mexico). */
public record CalibrationValidation(
        YearRange holdout,
        double mape,
        double corr,
        Map<String, ValidationScore> byCountry) {}
