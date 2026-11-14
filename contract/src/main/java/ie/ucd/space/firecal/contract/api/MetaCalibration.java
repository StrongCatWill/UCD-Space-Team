package ie.ucd.space.firecal.contract.api;

import ie.ucd.space.firecal.contract.YearRange;

public record MetaCalibration(
        YearRange overlap,
        YearRange train,
        YearRange holdout,
        double holdoutMape,
        double holdoutCorr) {}
