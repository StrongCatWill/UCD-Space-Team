#!/usr/bin/env python3
"""Generate deterministic MOCK data for the api `mock` profile.

Writes api/src/main/resources/mock/ in the same shapes as D1's out/ (see contract/ records):
  calibration.json, coverage.json, demo_areas.json, demo/{area}.json, demo_snapshot.json,
  forecast/{area}.json (mock-only), hotspots/{year}.csv
All numbers are FAKE (seeded RNG). harmonization_version = "h1.0-mock" so mock data is never mistaken for real.

Run from repo root:  python3 api/tools/gen_mock.py
"""
import csv
import json
import math
import os
import random
import statistics

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "mock")
VERSION = "h1.0-mock"
AS_OF = "2026-11-14T08:00:00Z"
SWITCH = (2025, 1)
CURRENT = (2026, 11)          # in_progress
VIIRS_SP_UNTIL = (2026, 6)    # months after this (and before CURRENT) are provisional
BASE_FROM, BASE_TO = 2003, 2025

AREAS = [
    # area_id, name, country, bbox [w,s,e,n], peak month, scale, big years
    ("norcal", "Northern California", "United_States", [-124.5, 38.0, -119.5, 42.0], 8, 400, {2018: 3.0, 2020: 4.0, 2021: 3.5}),
    ("bc_interior", "Interior British Columbia", "Canada", [-124.0, 49.0, -116.0, 54.0], 8, 250, {2017: 3.5, 2018: 3.0, 2023: 3.2}),
    ("mx_south", "Southern Mexico (placeholder)", "Mexico", [-94.5, 15.0, -86.5, 21.7], 4, 600, {2003: 2.0, 2011: 1.8, 2024: 2.2}),
]
SPC_LEVEL = {"norcal": "ELEVATED", "bc_interior": None, "mx_south": None}


def r1(x):
    return None if x is None else round(x + 0.0, 1)


def months(start, end):
    y, m = start
    while (y, m) <= end:
        yield y, m
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)


def ym(y, m):
    return f"{y:04d}-{m:02d}"


def pct(values, q):
    """Linear-interpolated percentile (numpy default)."""
    s = sorted(values)
    k = (len(s) - 1) * q
    lo, hi = math.floor(k), math.ceil(k)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def a_factor(month):
    return 0.40 + 0.05 * math.sin(month)  # fake a(r,m)


def build_area(area_id, name, country, bbox, peak, scale, big):
    rng = random.Random(area_id)
    mec = {}
    for y, m in months((2003, 1), CURRENT):
        season = math.exp(-((m - peak) ** 2) / (2 * 1.4 ** 2)) + 0.02
        year_f = big.get(y, rng.lognormvariate(0, 0.35))
        v = scale * season * year_f * rng.lognormvariate(0, 0.25)
        if (y, m) == CURRENT:
            v *= 14 / 30  # month half observed
        mec[(y, m)] = round(v, 1)

    stats = {}
    for m in range(1, 13):
        vals = [mec[(y, m)] for y in range(BASE_FROM, BASE_TO + 1)]
        stats[m] = dict(median=statistics.median(vals), mean=statistics.fmean(vals),
                        p25=pct(vals, 0.25), p75=pct(vals, 0.75),
                        p_any=sum(v > 0 for v in vals) / len(vals))

    def anomaly(v, ref):
        return None if ref == 0 else (v - ref) / ref * 100

    cells = []
    for (y, m), v in mec.items():
        st = stats[m]
        viirs = (y, m) >= SWITCH
        a = a_factor(m)
        raw = round(v / a) if viirs else round(v)
        status = "in_progress" if (y, m) == CURRENT else ("provisional" if (y, m) > VIIRS_SP_UNTIL else "ok")
        cells.append(dict(
            ym=ym(y, m), mec=r1(v), raw_count=raw,
            raw_sensor="VIIRS_NOAA20" if viirs else "MODIS_AQUA",
            median=r1(st["median"]), mean=r1(st["mean"]),
            anomaly_pct=r1(anomaly(v, st["median"])), anomaly_pct_mean=r1(anomaly(v, st["mean"])),
            ci_low=r1(raw * (a - 0.04)) if viirs else None,
            ci_high=r1(raw * (a + 0.04)) if viirs else None,
            source_flag="VIIRS_CAL" if viirs else "MODIS", status=status))
    by_ym = {c["ym"]: c for c in cells}

    w, s, e, n = bbox
    area_km2 = (e - w) * 111.32 * math.cos(math.radians((s + n) / 2)) * (n - s) * 110.57
    calendar = dict(aoi_area_km2=r1(area_km2), switch_month=ym(*SWITCH), cells=cells)

    yearly = []
    for y in range(2003, CURRENT[0] + 1):
        yearly.append(dict(year=y, mec=r1(sum(v for (yy, _), v in mec.items() if yy == y)),
                           complete=y < CURRENT[0]))
    complete = [t for t in yearly if t["complete"]]
    peak_year = max(complete, key=lambda t: t["mec"])
    peak_cell = max(cells, key=lambda c: c["mec"])
    this = by_ym[ym(*CURRENT)]
    ranked = sorted((c for c in cells if c["status"] == "ok" and c["anomaly_pct"] is not None),
                    key=lambda c: c["anomaly_pct"], reverse=True)[:5]
    summary = dict(
        cards=dict(total_mec=r1(sum(mec.values())),
                   this_month=dict(ym=this["ym"], mec=this["mec"], median=this["median"], anomaly_pct=this["anomaly_pct"]),
                   peak_year=dict(year=peak_year["year"], mec=peak_year["mec"]),
                   peak_month=dict(ym=peak_cell["ym"], mec=peak_cell["mec"])),
        seasonality=[dict(month=m, median=r1(stats[m]["median"]), p25=r1(stats[m]["p25"]), p75=r1(stats[m]["p75"]),
                          mean=r1(stats[m]["mean"]), this_year=mec.get((CURRENT[0], m)))
                     for m in range(1, 13)],
        yearly=yearly,
        yearly_mean=r1(statistics.fmean(t["mec"] for t in complete)),
        top_anomalies=[dict(ym=c["ym"], mec=c["mec"], median=c["median"], anomaly_pct=c["anomaly_pct"]) for c in ranked])

    # experimental estimate for next month (Dec 2026); recent = last 3 complete months
    recent = [(2026, 8), (2026, 9), (2026, 10)]
    s_raw = sum(mec[k] for k in recent) / sum(stats[k[1]]["median"] for k in recent)
    s_clip = min(2.0, max(0.5, s_raw))
    dec = stats[12]
    level = SPC_LEVEL[area_id]
    if country == "Mexico":
        official_status, official = "no_feed", []
    elif level is None:
        official_status, official = "none_intersecting", []
    else:
        official_status = "available"
        official = [dict(provider="NOAA SPC", product="Fire Weather Outlook Day 1", level=level,
                         level_label=level.title(), issued_at="2026-11-14T06:00:00Z",
                         valid_to="2026-11-15T12:00:00Z", link="https://www.spc.noaa.gov/products/fire_wx/")]
    expected = dec["median"] * s_clip
    forecast = dict(
        official_status=official_status, official=official,
        experimental=dict(target_month="2026-12", expected_mec=r1(expected), p25=r1(dec["p25"] * s_clip),
                          p75=r1(dec["p75"] * s_clip), p_any=r1(dec["p_any"]), scale_s=r1(s_clip),
                          method="baseline_median_x_recent_scale",
                          label="Experimental estimate (not an official forecast)"))

    def device_for(target, snapshot):
        idx = cells.index(by_ym[target])
        c = cells[idx]
        last12 = [x["mec"] for x in cells[max(0, idx - 11): idx + 1]]
        last12 = [None] * (12 - len(last12)) + last12
        outlook_level = ("above_normal" if expected > dec["p75"] else
                         "below_normal" if expected < dec["p25"] else "near_normal")
        off_level = None if snapshot else level
        return dict(area_id=area_id, area_name=name, as_of=AS_OF, month=c["ym"], mec=c["mec"],
                    median=c["median"], anomaly_pct=c["anomaly_pct"], last12=last12,
                    outlook=dict(level=outlook_level, source="experimental"),
                    official=dict(level=off_level,
                                  source="no_feed" if country == "Mexico" else "NOAA SPC",
                                  issued=None if off_level is None else "2026-11-14T06:00:00Z"),
                    alert=(c["anomaly_pct"] or 0) >= 100 or off_level in ("ELEVATED", "CRITICAL", "EXTREME"),
                    snapshot=snapshot)

    device = device_for("2026-10", False)  # last month that is not in progress
    demo = dict(calendar=calendar, summary=summary, device=device)
    return demo, forecast, device_for


def calibration():
    def rows(region_id, parent, level):
        return [dict(region_id=region_id, parent=parent, month=m, a=round(a_factor(m), 3),
                     a_p25=round(a_factor(m) - 0.04, 3), a_p75=round(a_factor(m) + 0.04, 3),
                     n_years=6, level=level) for m in range(1, 13)]
    return dict(
        harmonization_version=VERSION, generated_at=AS_OF, train={"from": 2018, "to": 2023},
        switch_month=ym(*SWITCH), series_start="2003-01",
        regions=rows("B_35_-125", "United_States", "region") + rows("B_50_-125", "Canada", "region"),
        country=rows("United_States", "United_States", "country") + rows("Canada", "Canada", "country")
        + rows("Mexico", "Mexico", "country"),
        north_america=rows("NA", None, "north_america"),
        validation=dict(holdout={"from": 2024, "to": 2025}, mape=18.3, corr=0.94,
                        by_country={"United_States": {"mape": 15.1, "corr": 0.95},
                                    "Canada": {"mape": 22.0, "corr": 0.91},
                                    "Mexico": {"mape": 19.4, "corr": 0.93}}))


def hotspots():
    rng = random.Random("hotspots")
    rows = {2021: [], 2026: []}
    plan = [("norcal", 2021, 8), ("bc_interior", 2021, 7), ("mx_south", 2021, 4),
            ("norcal", 2026, 10), ("bc_interior", 2026, 9), ("mx_south", 2026, 10)]
    for area_id, y, m in plan:
        _, _, country, (w, s, e, n), *_ = next(a for a in AREAS if a[0] == area_id)
        viirs = (y, m) >= SWITCH
        for _ in range(60):
            d = rng.randint(1, 28)
            hhmm = rng.choice([2005, 2110, 2145, 950, 1030]) if viirs else rng.choice([2040, 2115, 905, 1000])
            conf = rng.choices(["low", "nominal", "high"], [1, 3, 1])[0]
            rows[y].append(dict(
                sensor="VIIRS" if viirs else "MODIS", satellite="N20" if viirs else "Aqua",
                latitude=round(rng.uniform(s, n), 4), longitude=round(rng.uniform(w, e), 4),
                acq_date=f"{y:04d}-{m:02d}-{d:02d}", acq_time_utc=f"{hhmm // 100:02d}:{hhmm % 100:02d}",
                daynight="D" if 1500 <= hhmm <= 2300 else "N", confidence_class=conf,
                frp_mw=round(rng.lognormvariate(2, 1), 1),
                type="" if (y, m) > VIIRS_SP_UNTIL else rng.choices(["0", "2"], [19, 1])[0],
                data_source="NRT" if (y, m) > VIIRS_SP_UNTIL else "SP", parent=country))
    cols = ["hotspot_id", "sensor", "satellite", "latitude", "longitude", "acq_date", "acq_time_utc",
            "daynight", "confidence_class", "frp_mw", "type", "data_source", "parent"]
    for y, rs in rows.items():
        rs.sort(key=lambda r: (r["acq_date"], r["acq_time_utc"]))
        seq = {}
        with open(os.path.join(OUT, "hotspots", f"{y}.csv"), "w", newline="") as f:
            wr = csv.DictWriter(f, fieldnames=cols, lineterminator="\n")
            wr.writeheader()
            for r in rs:
                key = (r["sensor"][0], r["acq_date"].replace("-", ""))
                seq[key] = seq.get(key, 0) + 1
                r["hotspot_id"] = f"{key[0]}-{key[1]}-{seq[key]:06d}"
                wr.writerow(r)


def dump(path, obj):
    with open(os.path.join(OUT, path), "w") as f:
        json.dump(obj, f, indent=1)
        f.write("\n")


def main():
    for sub in ("demo", "forecast", "hotspots"):
        os.makedirs(os.path.join(OUT, sub), exist_ok=True)
    snapshot = None
    for area in AREAS:
        demo, forecast, device_for = build_area(*area)
        dump(f"demo/{area[0]}.json", demo)
        dump(f"forecast/{area[0]}.json", forecast)
        if area[0] == "norcal":
            snapshot = device_for("2021-08", True)  # clearly above normal
    dump("demo_snapshot.json", snapshot)
    dump("demo_areas.json", [dict(area_id=a[0], name=a[1], country=a[2], bbox=a[3]) for a in AREAS])
    dump("calibration.json", calibration())
    dump("coverage.json", {"MODIS_AQUA": {"from": "2002-07", "sp_until": "2026-08-31"},
                           "VIIRS_NOAA20": {"from": "2018-01", "sp_until": "2026-06-30"}})
    hotspots()
    print("mock written to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
