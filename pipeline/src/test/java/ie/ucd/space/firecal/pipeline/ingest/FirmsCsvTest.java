package ie.ucd.space.firecal.pipeline.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.pipeline.download.FirmsProduct;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing rules from firms_monthly.py; sample rows are the observed headers in the spec. */
class FirmsCsvTest {

    static final String MODIS_SP = """
            latitude,longitude,brightness,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_t31,frp,daynight,type
            30.8806,-115.8307,306.7,1.5,1.2,2020-08-15,534,Terra,MODIS,61,6.03,296.6,8.2,N,0
            38.8142,-93.5539,300.8,1,1,2003-01-01,0423,Aqua,MODIS,80,6.2,267,10.4,D,2
            """;
    static final String VIIRS_NRT = """
            latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_ti5,frp,daynight
            36.92097,-119.03965,305.39,0.5,0.7,2026-11-14,900,N20,VIIRS,n,2.1URT,275.62,2.3,N
            """;

    static List<Detection> parse(String csv, FirmsProduct p) throws IOException {
        List<Detection> out = new ArrayList<>();
        FirmsCsv.read(new StringReader(csv), "test.csv", p, Country.Mexico, out::add);
        return out;
    }

    @Test
    void parsesModisSpRow() throws IOException {
        List<Detection> d = parse(MODIS_SP, FirmsProduct.MODIS);
        assertEquals(2, d.size());
        Detection a = d.get(0);
        assertEquals(Satellite.TERRA, a.satellite());
        assertEquals(30.8806, a.lat());
        assertEquals(2020, a.year());
        assertEquals(8, a.month());
        assertEquals(534, a.acqTime()); // 05:34, not zero-padded
        assertEquals(ConfidenceClass.nominal, a.confidence());
        assertEquals(0, a.type());
        assertEquals(false, a.provisional());
        assertEquals(Satellite.AQUA, d.get(1).satellite());
        assertEquals(ConfidenceClass.high, d.get(1).confidence()); // 80 = high
        assertEquals(423, d.get(1).acqTime());
    }

    @Test
    void parsesViirsNrtRow() throws IOException {
        Detection v = parse(VIIRS_NRT, FirmsProduct.VIIRS).get(0);
        assertEquals(Satellite.NOAA20, v.satellite());
        assertEquals(ConfidenceClass.nominal, v.confidence());
        assertEquals(-1, v.type());         // NRT: no type column
        assertTrue(v.provisional());        // version 2.1URT
    }

    @Test
    void confidenceBoundaries() {
        assertEquals(ConfidenceClass.low, FirmsCsv.confidence("29"));
        assertEquals(ConfidenceClass.nominal, FirmsCsv.confidence("30"));
        assertEquals(ConfidenceClass.nominal, FirmsCsv.confidence("79"));
        assertEquals(ConfidenceClass.high, FirmsCsv.confidence("80"));
        assertEquals(ConfidenceClass.high, FirmsCsv.confidence("h"));
        assertEquals(ConfidenceClass.low, FirmsCsv.confidence("low"));
    }

    @Test
    void failsLoudlyOnUnknownSatelliteAndMissingHeader() {
        String badSat = VIIRS_NRT.replace(",N20,", ",1,");
        Exception e = assertThrows(IllegalStateException.class, () -> parse(badSat, FirmsProduct.VIIRS));
        assertTrue(e.getMessage().contains("unknown satellite code '1'"), e.getMessage());
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());

        String noFrp = MODIS_SP.replace(",frp,", ",xfrp,");
        e = assertThrows(IllegalStateException.class, () -> parse(noFrp, FirmsProduct.MODIS));
        assertTrue(e.getMessage().contains("missing required columns [frp]"), e.getMessage());
    }
}
