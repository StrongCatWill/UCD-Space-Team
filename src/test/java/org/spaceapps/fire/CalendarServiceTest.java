package org.spaceapps.fire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.spaceapps.fire.calendar.CalendarService;
import org.spaceapps.fire.calendar.OutlookService;
import org.spaceapps.fire.dto.OutlookResponse;
import org.spaceapps.fire.dto.Bbox;
import org.spaceapps.fire.dto.CalendarFile;
import org.spaceapps.fire.dto.CalendarResponse;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class CalendarServiceTest {

    /** 3 years of a single cell: Jan..Dec = 1..12 each year, last month partial. */
    private static CalendarService tiny() {
        List<String> months = new java.util.ArrayList<>();
        double[] h = new double[36];
        for (int i = 0; i < 36; i++) {
            months.add(String.format("%d-%02d", 2020 + i / 12, i % 12 + 1));
            h[i] = i % 12 + 1 + (i / 12 == 1 ? 10 : 0);            // 2021 is a busy year
        }
        var cell = new CalendarFile.Cell("80_-240", 40.25, -119.75, h);
        var outside = new CalendarFile.Cell("10_10", 5.25, 5.25, new double[36]);
        return new CalendarService(new CalendarFile(0.5, "hotspots", months, "2021-02", "2022-12-07", List.of(cell, outside)));
    }

    @Test
    void sumsCellsInsideBboxAndComputesMedians() {
        CalendarResponse r = tiny().calendar(Bbox.parse("-125,32,-114,42"));
        assertThat(r.cellCount()).isEqualTo(1);
        assertThat(r.years()).containsExactly(2020, 2021, 2022);
        assertThat(r.matrix().get(1).get(0)).isEqualTo(11.0);           // Jan 2021
        assertThat(r.monthlyMedian().get(0)).isEqualTo(1.0);            // Jan: 1, 11, 1 -> median 1
        assertThat(r.latestCompleteMonth().month()).isEqualTo("2022-11"); // Dec 2022 is partial
        assertThat(r.peakMonth()).isEqualTo(12);
        assertThat(r.topYears().getFirst().year()).isEqualTo(2021);
    }

    @Test
    void csvMarksSourcesAndPartialMonth() {
        String csv = tiny().csv(Bbox.parse("-125,32,-114,42"));
        assertThat(csv).startsWith("month,hotspots_noaa20_equivalent,source,cells");
        assertThat(csv).contains("2021-01,11.0,MODISxk,1").contains("2021-02,12.0,VIIRS,1").contains("2022-12,12.0,VIIRS-partial,1");
    }

    @Test
    void hotspotsCsvListsNonZeroCellMonthsInRange() {
        String csv = tiny().hotspotsCsv(Bbox.parse("-125,32,-114,42"), "2021-01", "2021-02");
        assertThat(csv.lines().toList()).containsExactly(
                "month,cellId,lat,lon,hotspots_noaa20_equivalent,source",
                "2021-01,80_-240,40.25,-119.75,11.0,MODISxk",
                "2021-02,80_-240,40.25,-119.75,12.0,VIIRS");
    }

    @Test
    void hotspotsCsvDefaultsToFullRangeAndRejectsReversedRange() {
        assertThat(tiny().hotspotsCsv(Bbox.parse("-125,32,-114,42"), null, null).lines().count()).isEqualTo(37);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tiny().hotspotsCsv(Bbox.parse("-125,32,-114,42"), "2022-01", "2021-01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activityRecordsCarryCompletenessSourceAndDailyMean() {
        var a = tiny().activity(Bbox.parse("-125,32,-114,42"));
        assertThat(a.records()).hasSize(36);
        var jan21 = a.records().get(12);
        assertThat(jan21.harmonized()).isEqualTo(11.0);
        assertThat(jan21.viirs()).isNull();                          // before viirsNativeFrom 2021-02
        assertThat(jan21.modis()).isNull();
        assertThat(jan21.dailyMean()).isEqualTo(Math.round(11.0 / 31 * 100) / 100.0);
        assertThat(a.records().get(13).viirs()).isEqualTo(12.0);     // Feb 2021 is VIIRS-native
        var dec22 = a.records().get(35);
        assertThat(dec22.complete()).isFalse();
        assertThat(dec22.dailyMean()).isEqualTo(Math.round(12.0 / 7 * 100) / 100.0);   // 7 days of data
        assertThat(a.monthlyMean().get(0)).isEqualTo(4.3);           // Jan: (1 + 11 + 1) / 3
        assertThat(a.lastCompleteMonth()).isEqualTo("2022-11");
    }

    @Test
    void outlookUsesSameMonthDistributionAfterLastCompleteMonth() {
        var o = new OutlookService(tiny()).outlook(Bbox.parse("-125,32,-114,42"), 2);
        var forecast = o.points().stream().filter(p -> p.phase().equals("forecast")).toList();
        assertThat(forecast).extracting("month").containsExactly("2022-12", "2023-01");
        assertThat(forecast.getFirst().forecastMedian()).isEqualTo(17.0);   // Dec of 2020, 2021 = 12, 22
        assertThat(forecast.get(1).forecastMedian()).isEqualTo(1.0);        // Jan of 2020..2022 = 1, 11, 1
        assertThat(o.points().stream().filter(p -> p.phase().equals("historical"))).hasSize(35);
        assertThat(o.risks().getFirst().level()).isEqualTo("extreme");      // Dec is the peak month
        assertThat(o.isMock()).isFalse();
        assertThat(o.method()).isEqualTo("climatology");
        assertThatThrownBy(() -> new OutlookService(tiny()).outlook(Bbox.parse("-125,32,-114,42"), 13))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void realOutlookForCaliforniaIsOrderedAndPeaksInFireSeason() {
        CalendarService s = CalendarService.load(new ClassPathResource("data/calendar.json"), JsonMapper.builder().build());
        var o = new OutlookService(s).outlook(Bbox.parse("-124.5,32.5,-114,42"), 12);
        o.points().stream().filter(p -> p.phase().equals("forecast")).forEach(p -> {
            assertThat(p.lower95()).isLessThanOrEqualTo(p.lower80());
            assertThat(p.lower80()).isLessThanOrEqualTo(p.forecastMedian());
            assertThat(p.forecastMedian()).isLessThanOrEqualTo(p.upper80());
            assertThat(p.upper80()).isLessThanOrEqualTo(p.upper95());
        });
        var top = o.risks().stream().max(Comparator.comparingInt(OutlookResponse.Risk::score)).orElseThrow();
        assertThat(YearMonth.parse(top.month()).getMonthValue()).isBetween(6, 10);
        assertThat(o.modelConfidence().scorePercent()).isBetween(0, 100);
    }

    @Test
    void halfDegreeRoiAlwaysSelectsExactlyOneCell() {
        // 4 neighbouring cell centres around (40.0, -120.0)
        double[] h = new double[36];
        List<CalendarFile.Cell> cells = List.of(
                new CalendarFile.Cell("a", 39.75, -120.25, h), new CalendarFile.Cell("b", 39.75, -119.75, h),
                new CalendarFile.Cell("c", 40.25, -120.25, h), new CalendarFile.Cell("d", 40.25, -119.75, h));
        List<String> months = new java.util.ArrayList<>();
        for (int i = 0; i < 36; i++) months.add(String.format("%d-%02d", 2020 + i / 12, i % 12 + 1));
        var s = new CalendarService(new CalendarFile(0.5, "hotspots", months, "2021-02", "2022-12-07", cells));
        assertThat(s.calendar(Bbox.parse("-120.25,39.75,-119.75,40.25")).cellCount()).isEqualTo(1); // edges on centres
        assertThat(s.calendar(Bbox.parse("-120.1,39.9,-119.6,40.4")).cellCount()).isEqualTo(1);     // arbitrary offset
    }

    @Test
    void realCalendarFileLoads() {
        CalendarService s = CalendarService.load(new ClassPathResource("data/calendar.json"), JsonMapper.builder().build());
        CalendarResponse ca = s.calendar(Bbox.parse("-124.5,32.5,-114,42"));   // California
        assertThat(ca.cellCount()).isGreaterThan(50);
        assertThat(ca.years().getFirst()).isEqualTo(2000);
        assertThat(ca.peakMonth()).isBetween(6, 10);                     // summer/autumn fire season
    }
}
