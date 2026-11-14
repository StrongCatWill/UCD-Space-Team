"""FIRMS CSV (API or archive) -> normalized detections + monthly aggregates.

Usage:
  python firms_monthly.py fixtures/raw_*.csv --out data/
  python firms_monthly.py archive/*.csv --out data/ --keep-low   # keep low-confidence too

Outputs:
  data/detections.csv  one row per detection, normalized columns
  data/monthly.csv     sensor x year x month x daynight aggregates
"""
import argparse
import os
import sys

import pandas as pd

# satellite code -> series. Unknown codes are reported, not silently dropped.
SATELLITE_MAP = {
    "Terra": "MODIS", "T": "MODIS",
    "Aqua": "MODIS", "A": "MODIS",
    "N": "VIIRS_SNPP", "NPP": "VIIRS_SNPP", "Suomi NPP": "VIIRS_SNPP",
    "N20": "VIIRS_NOAA20", "1": "VIIRS_NOAA20", "NOAA-20": "VIIRS_NOAA20",
    "N21": "VIIRS_NOAA21", "2": "VIIRS_NOAA21", "NOAA-21": "VIIRS_NOAA21",
}
GRID_DEG = 0.5  # grid size for "cell-days" (resolution-robust activity metric)


def conf_category(s: pd.Series) -> pd.Series:
    """MODIS 0-100 -> l/n/h (FIRMS classes: <30 low, 30-79 nominal, >=80 high).
    VIIRS l/n/h or low/nominal/high -> first letter."""
    num = pd.to_numeric(s, errors="coerce")
    out = s.astype(str).str.strip().str.lower().str[0]
    is_num = num.notna()
    out[is_num] = pd.cut(num[is_num], [-1, 29, 79, 100], labels=["l", "n", "h"]).astype(str)
    return out


def normalize(path: str) -> pd.DataFrame:
    df = pd.read_csv(path, dtype={"satellite": str, "version": str, "confidence": str})
    if df.empty:
        return df
    df.columns = [c.strip().lower() for c in df.columns]

    df["series"] = df["satellite"].astype(str).str.strip().map(SATELLITE_MAP)
    unknown = df.loc[df["series"].isna(), "satellite"].unique()
    if len(unknown):
        print(f"[warn] {path}: unknown satellite codes {list(unknown)}", file=sys.stderr)

    df["conf"] = conf_category(df["confidence"])
    t = df["acq_time"].astype(int).astype(str).str.zfill(4)          # 534 -> 0534
    df["acq_dt_utc"] = pd.to_datetime(df["acq_date"] + " " + t, format="%Y-%m-%d %H%M", utc=True)
    # NRT / RT / URT versions get replaced by science-quality SP later
    df["provisional"] = df["version"].astype(str).str.upper().str.contains("RT")
    # type: 0 vegetation fire, 1 volcano, 2 other static land (e.g. gas flares), 3 offshore.
    # Only present in SP/archive data; NA for NRT.
    df["type"] = pd.to_numeric(df["type"], errors="coerce") if "type" in df else pd.NA
    df["brightness_k"] = df.get("brightness", df.get("bright_ti4"))
    df["cell"] = ((df["latitude"] // GRID_DEG).astype(int).astype(str) + "_" +
                  (df["longitude"] // GRID_DEG).astype(int).astype(str))
    df["source_file"] = os.path.basename(path)

    keep = ["series", "satellite", "latitude", "longitude", "acq_dt_utc", "acq_date",
            "daynight", "conf", "frp", "brightness_k", "type", "provisional", "cell", "source_file"]
    return df[keep]


def monthly(det: pd.DataFrame) -> pd.DataFrame:
    d = det.copy()
    d["year"] = d["acq_dt_utc"].dt.year
    d["month"] = d["acq_dt_utc"].dt.month
    g = d.groupby(["series", "year", "month", "daynight"])
    out = g.agg(
        count=("frp", "size"),
        frp_sum=("frp", "sum"),
        cell_days=("cell", lambda c: (c + "|" + d.loc[c.index, "acq_date"]).nunique()),
        provisional=("provisional", "any"),
    ).reset_index()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--out", default="data")
    ap.add_argument("--keep-low", action="store_true", help="keep low-confidence detections")
    args = ap.parse_args()

    det = pd.concat([normalize(f) for f in args.files], ignore_index=True)
    det = det.drop_duplicates(subset=["series", "latitude", "longitude", "acq_dt_utc"])
    n0 = len(det)
    if not args.keep_low:
        det = det[det["conf"].isin(["n", "h"])]
    det = det[det["type"].isna() | (det["type"] == 0)]          # drop volcano/static/offshore
    print(f"detections: {n0} raw -> {len(det)} after filters", file=sys.stderr)

    os.makedirs(args.out, exist_ok=True)
    det.to_csv(os.path.join(args.out, "detections.csv"), index=False)
    m = monthly(det)
    m.to_csv(os.path.join(args.out, "monthly.csv"), index=False)
    print(m.to_string(index=False))


if __name__ == "__main__":
    main()