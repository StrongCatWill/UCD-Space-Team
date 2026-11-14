package ie.ucd.space.firecal.contract.files;

/** Hold-out skill of MEC vs actual Aqua: MAPE in percent, Pearson correlation. */
public record ValidationScore(double mape, double corr) {}
