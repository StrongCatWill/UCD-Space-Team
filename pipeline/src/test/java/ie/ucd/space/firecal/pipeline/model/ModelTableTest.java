package ie.ucd.space.firecal.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ie.ucd.space.firecal.contract.ConfidenceClass;
import ie.ucd.space.firecal.contract.Country;
import ie.ucd.space.firecal.pipeline.ingest.Detection;
import ie.ucd.space.firecal.pipeline.ingest.Satellite;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelTableTest {

    static Detection det(Satellite s, Country c, double lat, double lon, int year, int month,
                         ConfidenceClass conf, double frp, int type) {
        return new Detection(s, c, lat, lon, year, month, 1, 1200, conf, frp, type, 'D', false);
    }

    static List<String> build(ModelTable t) throws IOException {
        StringWriter w = new StringWriter();
        t.write(w);
        return w.toString().lines().toList();
    }

    @Test
    void cellIdAndRegionUseCellCentre() {
        int k = ModelTable.cellKey(ModelTable.latIdx(38.4), ModelTable.lonIdx(-121.6));
        assertEquals("38.25_-121.75", ModelTable.cellId(ModelTable.centreLat(k), ModelTable.centreLon(k)));
        assertEquals("B_35_-125", ModelTable.regionBlock(38.25, -121.75));
        int s = ModelTable.cellKey(ModelTable.latIdx(-0.1), ModelTable.lonIdx(0.1));
        assertEquals("-0.25_0.25", ModelTable.cellId(ModelTable.centreLat(s), ModelTable.centreLon(s)));
    }

    @Test
    void denseRowsNullsBeforeCoverageAndFilters() throws IOException {
        ModelTable t = new ModelTable(2011, 2018, ConfidenceClass.nominal);
        // cell 38.25_-121.75, 2011-08: Terra nominal + Aqua high -> modis 2 (1+1), high 1, frp 10.25+5 / max 10.25
        t.accept(det(Satellite.TERRA, Country.United_States, 38.4, -121.6, 2011, 8, ConfidenceClass.nominal, 10.25, 0));
        t.accept(det(Satellite.AQUA, Country.United_States, 38.3, -121.9, 2011, 8, ConfidenceClass.high, 5.0, 0));
        // dropped: low confidence, static type 2, offshore type 3, out of year range
        t.accept(det(Satellite.AQUA, Country.United_States, 38.3, -121.9, 2011, 8, ConfidenceClass.low, 99, 0));
        t.accept(det(Satellite.AQUA, Country.United_States, 38.3, -121.9, 2011, 8, ConfidenceClass.high, 99, 2));
        t.accept(det(Satellite.AQUA, Country.United_States, 38.3, -121.9, 2011, 8, ConfidenceClass.high, 99, 3));
        t.accept(det(Satellite.AQUA, Country.United_States, 38.3, -121.9, 2019, 8, ConfidenceClass.high, 99, 0));
        // S-NPP 2012-03 and NOAA-20 2018-07 in the same cell
        t.accept(det(Satellite.SNPP, Country.United_States, 38.3, -121.9, 2012, 3, ConfidenceClass.nominal, 1.04, 0));
        t.accept(det(Satellite.NOAA20, Country.United_States, 38.3, -121.9, 2018, 7, ConfidenceClass.high, 2.0, 0));

        assertEquals(new ModelTable.Stats(8, 4, 1, 2, 1), t.stats());
        List<String> lines = build(t);
        assertEquals(String.join(",", ModelTable.COLUMNS), lines.get(0));
        assertEquals(1 + 8 * 12, lines.size()); // 1 active cell x 96 months, zero months included

        String pre = "38.25_-121.75,38.25,-121.75,B_35_-125,United_States,unknown,";
        assertEquals(pre + "2011,1,0,0,0,0,0.0,0.0,,,,", lines.get(1));                   // VIIRS null before coverage
        assertEquals(pre + "2011,8,2,1,1,1,15.3,10.3,,,,", lines.get(8));                 // 15.25 -> 15.3
        assertEquals(pre + "2012,1,0,0,0,0,0.0,0.0,0,0.0,,", lines.get(13));              // S-NPP observed, no fire = 0
        assertEquals(pre + "2012,3,0,0,0,0,0.0,0.0,1,1.0,,", lines.get(15));
        assertEquals(pre + "2018,7,0,0,0,0,0.0,0.0,0,0.0,1,2.0", lines.get(1 + 7 * 12 + 6));
    }

    @Test
    void borderCellTakesMajorityCountry() throws IOException {
        ModelTable t = new ModelTable(2020, 2020, ConfidenceClass.nominal);
        t.accept(det(Satellite.AQUA, Country.United_States, 49.1, -100.1, 2020, 5, ConfidenceClass.high, 1, 0));
        t.accept(det(Satellite.AQUA, Country.Canada, 49.2, -100.2, 2020, 5, ConfidenceClass.high, 1, 0));
        t.accept(det(Satellite.AQUA, Country.Canada, 49.3, -100.3, 2020, 6, ConfidenceClass.high, 1, 0));
        assertEquals("Canada", build(t).get(1).split(",")[4]);
    }
}
