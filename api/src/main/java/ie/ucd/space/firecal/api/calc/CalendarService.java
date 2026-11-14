package ie.ucd.space.firecal.api.calc;

import ie.ucd.space.firecal.api.data.DataStore;
import ie.ucd.space.firecal.contract.api.CalendarCell;
import ie.ucd.space.firecal.contract.api.CalendarData;
import ie.ucd.space.firecal.contract.files.DemoAreaFile;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** POST /api/calendar data. Demo areas come straight from out/demo/{area_id}.json when present. */
@Service
public class CalendarService {

    public CalendarData calendar(AoiQuery q, DataStore store) {
        Optional<DemoAreaFile> demo = demoFile(q, store);
        if (demo.isPresent() && demo.get().calendar() != null) {
            CalendarData d = demo.get().calendar();
            List<CalendarCell> cells = d.cells().stream()
                    .filter(c -> !c.ym().isBefore(q.start()) && !c.ym().isAfter(q.end())).toList();
            return new CalendarData(d.aoiAreaKm2(), d.switchMonth(), cells);
        }
        return calendar(q, store, series(q, store));
    }

    public CalendarData calendar(AoiQuery q, DataStore store, AoiSeries series) {
        List<CalendarCell> cells = new ArrayList<>();
        for (YearMonth ym = q.start(); !ym.isAfter(q.end()); ym = ym.plusMonths(1)) cells.add(series.cell(ym));
        return new CalendarData(Stats.r1(q.aoi().areaKm2()), store.calibration().switchMonth(), cells);
    }

    public static AoiSeries series(AoiQuery q, DataStore store) {
        return AoiSeries.compute(store, q.aoi(), q.start(), q.end(), q.baseline(), q.includeStatic(), q.currentYm());
    }

    static Optional<DemoAreaFile> demoFile(AoiQuery q, DataStore store) {
        return q.demoCalendarUsable() ? store.demoFile(q.aoi().demoAreaId()) : Optional.empty();
    }
}
