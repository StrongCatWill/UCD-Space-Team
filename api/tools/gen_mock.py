#!/usr/bin/env python3
"""Generate api/src/main/resources/mock/* — synthetic data in the exact out/ shapes (contract/).

Deterministic (fixed seed). Run from the repo root:  python3 api/tools/gen_mock.py
Numbers are FAKE; harmonization_version is "mock-h1.0" so nobody mistakes them for real data.
"""
import csv, json, math, os, random

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "mock")
rnd = random.Random(20261114)

SWITCH = (2025, 1)
SERIES_START = (2003, 1)
LAST = (2026, 11)          # current month (in_progress)
SP_UNTIL_VIIRS = (2026, 6)  # mock: NOAA-20 SP ends 2026-06-30, NRT after

# (parent, centre lat, centre lon, base activity, seasonal peak month)
CELLS = [
    ("United_States", 39.55, -121.45, 30, 8), ("United_States", 40.05, -122.35, 22, 8),
    ("United_States", 41.25, -123.05, 15, 9), ("United_States", 38.65, -120.55, 18, 7),
    ("Canada", 50.65, -120.35, 20, 8), ("Canada", 51.95, -122.15, 14, 7),
    ("Canada", 49.75, -119.45, 10, 8), ("Canada", 52.85, -121.65, 8, 7),
    ("Mexico", 26.15, -107.25, 25, 5), ("Mexico", 27.45, -106.85, 18, 4),
    ("Mexico", 25.05, -105.55, 12, 5), ("Mexico", 28.35, -107.95, 9, 5),
    ("United_States", 31.05, -98.45, 12, 3),   # Texas, outside demo areas
    ("Canada", 55.15, -114.25, 9, 6),          # Alberta, outside demo areas
]


def cell_id(lat, lon):
    return f"{math.floor(lat * 10)}_{math.floor(lon * 10)}"


def block(lat, lon):
    return f"B_{math.floor(lat / 5) * 5}_{math.floor(lon / 5) * 5}"


def months():
    y, m = SERIES_START
    while (y, m) <= LAST:
        yield y, m
        m += 1
        if m == 13:
            y, m = y + 1, 1


def a_for(region, month):
    # deterministic pseudo-factor around 0.35
    h = sum(map(ord, region)) + month * 7
    return round(0.30 + (h % 11) / 100, 3)


cell_month, regions = [], {}
for parent, lat, lon, base, peak in CELLS:
    year_factor = {y: rnd.uniform(0.4, 1.8) for y in range(2003, 2027)}
    year_factor[2021] = 2.6 if parent == "United_States" else year_factor[2021]
    year_factor[2023] = 3.0 if parent == "Canada" else year_factor[2023]
    for y, m in months():
        season = math.exp(-((m - peak) ** 2) / 4.0)
        expected = base * season * year_factor[y]
        if (y, m) == (2026, 8) and parent == "United_States":
            expected *= 4  # one clearly above-normal month for the device demo
        modis_count = max(0, int(round(expected + rnd.gauss(0, 1.5))))
        viirs = (y, m) >= SWITCH
        r = block(lat, lon)
        regions.setdefault(r, parent)
        if viirs:
            a = a_for(r, m)
            raw = int(round(modis_count / a))
            mec = round(a * raw, 1)
        else:
            raw, mec = modis_count, float(modis_count)
        if raw == 0:
            continue  # sparse: zero-detection months are absent; coverage decides no_data
        static_extra = 2.0 if cell_id(lat, lon) == "395_-1215" else 0.0
        cell_month.append({
            "cell_id": cell_id(lat, lon), "lat": lat, "lon": lon, "parent": parent,
            "ym": f"{y:04d}-{m:02d}", "mec": mec, "mec_with_static": round(mec + static_extra, 1),
            "raw_count": raw, "raw_sensor": "VIIRS_NOAA20" if viirs else "MODIS_AQUA",
            "source_flag": "VIIRS_CAL" if viirs else "MODIS",
            "provisional": viirs and (y, m) > SP_UNTIL_VIIRS,
        })


def factor(region_id, parent, m, level, n_years=6):
    a = a_for(region_id, m)
    return {"region_id": region_id, "parent": parent, "month": m, "a": a,
            "a_p25": round(a * 0.85, 3), "a_p75": round(a * 1.15, 3), "n_years": n_years, "level": level}


calibration = {
    "harmonization_version": "mock-h1.0",
    "generated_at": "2026-11-14T08:00:00Z",
    "train": {"from": 2018, "to": 2023},
    "switch_month": "2025-01",
    "series_start": "2003-01",
    "regions": [factor(r, p, m, "region") for r, p in sorted(regions.items()) for m in range(1, 13)],
    "country": [factor(c, c, m, "country") for c in ("United_States", "Canada", "Mexico") for m in range(1, 13)],
    "north_america": [factor("NA", None, m, "north_america") for m in range(1, 13)],
    "validation": {"holdout": {"from": 2024, "to": 2025}, "mape": 18.4, "corr": 0.91,
                   "by_country": {"United_States": {"mape": 15.2, "corr": 0.93},
                                  "Canada": {"mape": 21.7, "corr": 0.88},
                                  "Mexico": {"mape": 19.9, "corr": 0.9}}},
}

coverage = {"MODIS_AQUA": {"from": "2002-07-04", "sp_until": "2026-08-31"},
            "VIIRS_NOAA20": {"from": "2018-01-01", "sp_until": "2026-06-30"}}

snapshot = {
    "area_id": "norcal", "area_name": "Northern California", "as_of": "2026-09-01T08:00:00Z",
    "month": "2026-08", "mec": 412.0, "median": 118.0, "anomaly_pct": 249.2,
    "last12": [3.0, 1.0, 0.0, 0.0, 2.0, 4.0, 9.0, 15.0, 38.0, 96.0, 180.0, 412.0],
    "outlook": {"level": "above_normal", "source": "experimental"},
    "official": {"level": "CRITICAL", "source": "NOAA SPC Fire Weather Outlook", "issued": "2026-08-31T17:00:00Z"},
    "alert": True, "snapshot": True,
}

os.makedirs(os.path.join(OUT, "hotspots"), exist_ok=True)
dump = lambda name, obj: json.dump(obj, open(os.path.join(OUT, name), "w"), indent=None, separators=(",", ":"))
dump("cell_month.json", cell_month)
dump("calibration.json", calibration)
dump("coverage.json", coverage)
dump("demo_snapshot.json", snapshot)

COLS = ["hotspot_id", "sensor", "satellite", "latitude", "longitude", "acq_date", "acq_time_utc", "daynight",
        "confidence_class", "frp_mw", "type", "data_source", "parent"]
for year in (2025, 2026):
    rows = []
    for parent, lat, lon, base, peak in CELLS:
        for _ in range(25):
            m = max(1, min(12 if year == 2025 else 11, int(round(rnd.gauss(peak, 1.5)))))
            d = rnd.randint(1, 28)
            hh, mm = rnd.choice([(9, 30), (10, 12), (20, 48), (21, 6)])
            data_source = "NRT" if (year, m) > SP_UNTIL_VIIRS else "SP"
            rows.append({
                "sensor": "VIIRS_NOAA20", "satellite": "N20",
                "latitude": round(lat + rnd.uniform(-0.049, 0.049), 5),
                "longitude": round(lon + rnd.uniform(-0.049, 0.049), 5),
                "acq_date": f"{year}-{m:02d}-{d:02d}", "acq_time_utc": f"{hh:02d}:{mm:02d}",
                "daynight": "D" if hh < 18 else "N",
                "confidence_class": rnd.choice(["low", "nominal", "nominal", "high"]),
                "frp_mw": round(rnd.uniform(0.5, 60), 1),
                "type": "" if data_source == "NRT" else rnd.choice(["0", "0", "0", "0", "2"]),
                "data_source": data_source, "parent": parent,
            })
    rows.sort(key=lambda r: (r["acq_date"], r["acq_time_utc"]))
    with open(os.path.join(OUT, "hotspots", f"{year}.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLS, lineterminator="\n")
        w.writeheader()
        for i, r in enumerate(rows, 1):
            r["hotspot_id"] = f"V-{r['acq_date'].replace('-', '')}-{i:06d}"
            w.writerow(r)

print(f"cell_month={len(cell_month)} regions={len(calibration['regions'])} -> {os.path.abspath(OUT)}")
