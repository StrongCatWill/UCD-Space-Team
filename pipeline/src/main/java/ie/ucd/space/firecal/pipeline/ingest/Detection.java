package ie.ucd.space.firecal.pipeline.ingest;

import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.Country;

/**
 * One normalized FIRMS detection. Created per row while streaming and never collected in bulk.
 *
 * @param acqTime    HHMM UTC as an int (534 = 05:34)
 * @param type       0 vegetation, 1 volcano, 2 static land, 3 offshore; -1 when the file has no type column (NRT)
 * @param provisional version contains "RT" (NRT/URT)
 */
public record Detection(Satellite satellite, Country parent, double lat, double lon,
                        int year, int month, int day, int acqTime,
                        ConfidenceClass confidence, double frp, int type, char daynight, boolean provisional) {

    /** Vegetation fire (type 0) or unknown type (NRT). Types 1/2 = static, 3 = offshore. */
    public boolean isVegetation() {
        return type == 0 || type == -1;
    }
}
