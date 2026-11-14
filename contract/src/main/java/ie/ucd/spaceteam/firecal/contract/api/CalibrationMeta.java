package ie.ucd.spaceteam.firecal.contract.api;

import ie.ucd.spaceteam.firecal.contract.data.YearRange;

public record CalibrationMeta(
        YearRange overlap,
        YearRange train,
        YearRange holdout,
        Double holdoutMape,
        Double holdoutCorr) {}
