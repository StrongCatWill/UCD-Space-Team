package org.spaceapps.fire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.spaceapps.fire.calendar.CalendarService;
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
    void realCalendarFileLoads() {
        CalendarService s = CalendarService.load(new ClassPathResource("data/calendar.json"), JsonMapper.builder().build());
        CalendarResponse ca = s.calendar(Bbox.parse("-124.5,32.5,-114,42"));   // California
        assertThat(ca.cellCount()).isGreaterThan(50);
        assertThat(ca.years().getFirst()).isEqualTo(2000);
        assertThat(ca.peakMonth()).isBetween(6, 10);                     // summer/autumn fire season
    }
}
