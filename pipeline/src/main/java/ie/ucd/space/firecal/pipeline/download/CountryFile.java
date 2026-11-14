package ie.ucd.space.firecal.pipeline.download;

import ie.ucd.space.firecal.contract.Country;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;

/** One FIRMS country yearly CSV, e.g. modis/2003/modis_2003_Canada.csv. */
public record CountryFile(FirmsProduct product, int year, Country country) {

    /** File name as published by FIRMS; also the name used in archive/. */
    public String fileName() {
        return product.token + "_" + year + "_" + country.name() + ".csv";
    }

    public URI url() {
        return URI.create(FirmsProduct.BASE_URL + product.token + "/" + year + "/" + fileName());
    }

    /**
     * All files for the given selection, in deterministic order (product, year, country).
     * Years before a product's first published year are dropped (e.g. VIIRS before 2018).
     */
    public static List<CountryFile> plan(Collection<FirmsProduct> products, SortedSet<Integer> years,
                                         Collection<Country> countries) {
        List<CountryFile> files = new ArrayList<>();
        for (FirmsProduct p : FirmsProduct.values()) {
            if (!products.contains(p)) continue;
            for (int y : years) {
                if (y < p.firstYear) continue;
                for (Country c : Country.values()) {
                    if (countries.contains(c)) files.add(new CountryFile(p, y, c));
                }
            }
        }
        return files;
    }
}
