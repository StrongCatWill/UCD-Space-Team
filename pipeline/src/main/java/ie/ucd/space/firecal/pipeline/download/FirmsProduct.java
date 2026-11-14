package ie.ucd.space.firecal.pipeline.download;

/** FIRMS country yearly CSV products used by the pipeline (no key needed). */
public enum FirmsProduct {
    /** MODIS C6.1 SP (Terra + Aqua in one file). Files exist from 2000. */
    MODIS("modis", 2000),
    /** VIIRS NOAA-20 (JPSS-1) SP. Files exist from 2018. */
    VIIRS("viirs-jpss1", 2018),
    /**
     * VIIRS S-NPP SP. Files exist from 2012. NOT part of the calendar series (spec: no S-NPP); used only as the
     * VIIRS target of the models/ table, so it is downloaded only with {@code --only snpp}.
     */
    SNPP("viirs-snpp", 2012);

    public static final String BASE_URL = "https://firms.modaps.eosdis.nasa.gov/data/country/";

    /** Path / file-name token used by FIRMS, e.g. "viirs-jpss1". */
    public final String token;
    /** First year FIRMS publishes for this product; earlier years are never requested. */
    public final int firstYear;

    FirmsProduct(String token, int firstYear) {
        this.token = token;
        this.firstYear = firstYear;
    }
}
