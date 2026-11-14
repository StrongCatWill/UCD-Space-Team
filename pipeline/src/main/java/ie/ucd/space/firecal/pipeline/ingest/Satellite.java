package ie.ucd.space.firecal.pipeline.ingest;

import ie.ucd.space.firecal.contract.Series;
import ie.ucd.space.firecal.pipeline.download.FirmsProduct;

/**
 * Satellite of one detection. Pipeline-internal: S-NPP exists only for the models/ table and has no contract
 * {@link Series} (spec: no S-NPP in the calendar).
 */
public enum Satellite {
    TERRA(Series.MODIS_TERRA),
    AQUA(Series.MODIS_AQUA),
    NOAA20(Series.VIIRS_NOAA20),
    SNPP(null);

    /** Contract series, or null when the satellite is not part of the calendar contract. */
    public final Series series;

    Satellite(Series series) {
        this.series = series;
    }

    /**
     * Maps the FIRMS {@code satellite} code for a given product. Unknown codes throw: the spec says to fail
     * loudly rather than drop rows. Verified codes: MODIS "Terra"/"Aqua"; viirs-jpss1 "N20" (all 2018-2025 files).
     */
    public static Satellite of(FirmsProduct product, String code) {
        String c = code.trim();
        Satellite s = switch (product) {
            case MODIS -> switch (c) {
                case "Terra", "T" -> TERRA;
                case "Aqua", "A" -> AQUA;
                default -> null;
            };
            case VIIRS -> switch (c) {
                case "N20" -> NOAA20;
                default -> null;
            };
            case SNPP -> switch (c) {
                case "N", "NPP", "Suomi NPP" -> SNPP;
                default -> null;
            };
        };
        if (s == null) {
            throw new IllegalStateException("unknown satellite code '" + c + "' in a " + product.token + " file");
        }
        return s;
    }
}
