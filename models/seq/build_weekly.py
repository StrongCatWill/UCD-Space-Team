"""FIRMS archive CSVs -> sparse weekly 0.25 deg cell table for the sequence backcast.

Usage (from models/):
  .venv/bin/python -m seq.build_weekly --archive ../../archive/archive --out data/seq

Reads modis_YYYY_*.csv and viirs-snpp_YYYY_*.csv (NOAA-20/21 are ignored on purpose: their launch dates
would show up as step changes in the target). Keeps type == 0 (presumed vegetation fire) only, which drops
volcanoes, static industrial sources (gas flares) and offshore detections.

Writes (to --out):
  cell_week.parquet      one row per (cell, week) with ANY detection; zeros are implicit
  sensor_daily.parquet   NA-wide detections per (date, satellite): used to find sensor outages
  overpass_week.parquet  per (week, satellite) mean local solar time of daytime detections (orbit drift)

week = days since 2000-01-03 (a Monday) // 7.  cell = (floor(lat/0.25), floor(lon/0.25)).
"""
import argparse
import glob
import os
import re
import time

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.csv as pacsv

CELL_DEG = 0.25
EPOCH = np.datetime64("2000-01-03")

MODIS_SAT = {"Terra": "T", "T": "T", "Aqua": "A", "A": "A"}


def read_one(path: str) -> pd.DataFrame:
    cols = ["latitude", "longitude", "acq_date", "acq_time", "satellite", "confidence", "frp", "daynight",
            "type"]
    tbl = pacsv.read_csv(
        path,
        convert_options=pacsv.ConvertOptions(
            include_columns=cols,
            column_types={"acq_time": pa.string(), "confidence": pa.string(), "satellite": pa.string(),
                          "acq_date": pa.date32()},
        ),
    )
    df = tbl.to_pandas()
    df = df[df["type"] == 0]
    return df


def prep(df: pd.DataFrame, sensor: str) -> pd.DataFrame:
    d = pd.DataFrame()
    d["lat_i"] = np.floor(df["latitude"].to_numpy() / CELL_DEG).astype(np.int16)
    d["lon_i"] = np.floor(df["longitude"].to_numpy() / CELL_DEG).astype(np.int16)
    date = df["acq_date"].to_numpy().astype("datetime64[D]")
    d["date"] = date
    d["week"] = ((date - EPOCH).astype(np.int64) // 7).astype(np.int16)
    d["frp"] = df["frp"].to_numpy(dtype=np.float32)
    d["day"] = (df["daynight"].to_numpy() == "D").astype(np.int8)
    hhmm = df["acq_time"].str.zfill(4)
    utc_min = hhmm.str[:2].astype(int) * 60 + hhmm.str[2:].astype(int)
    d["lst_h"] = (((utc_min + df["longitude"] * 4.0) % 1440) / 60.0).astype(np.float32).to_numpy()
    if sensor == "modis":
        sat = df["satellite"].map(MODIS_SAT)
        if sat.isna().any():
            raise ValueError(f"unknown MODIS satellite codes: {df.loc[sat.isna(), 'satellite'].unique()}")
        d["sat"] = sat.to_numpy()
        conf = pd.to_numeric(df["confidence"], errors="coerce").to_numpy(dtype=np.float32)
        d["conf"] = conf
    else:
        d["sat"] = "N"
        d["conf"] = df["confidence"].str[0].map({"l": 0.0, "n": 50.0, "h": 100.0}).to_numpy(dtype=np.float32)
    return d


def agg_modis(d: pd.DataFrame) -> pd.DataFrame:
    d = d.assign(
        terra=(d["sat"] == "T").astype(np.int32),
        aqua=(d["sat"] == "A").astype(np.int32),
        night=(1 - d["day"]).astype(np.int32),
        hi=(d["conf"] >= 80).astype(np.int32),
        lo=(d["conf"] < 30).astype(np.int32),
        frp_t=np.where(d["sat"] == "T", d["frp"], 0).astype(np.float32),
    )
    g = d.groupby(["lat_i", "lon_i", "week"], sort=False)
    out = g.agg(
        m_n=("frp", "size"), m_terra=("terra", "sum"), m_aqua=("aqua", "sum"), m_day=("day", "sum"),
        m_night=("night", "sum"), m_hi=("hi", "sum"), m_lo=("lo", "sum"), m_frp=("frp", "sum"),
        m_frp_max=("frp", "max"), m_frp_terra=("frp_t", "sum"), m_conf_sum=("conf", "sum"),
        m_days=("date", "nunique"),
    )
    return out


def agg_viirs(d: pd.DataFrame) -> pd.DataFrame:
    g = d.groupby(["lat_i", "lon_i", "week"], sort=False)
    return g.agg(v_n=("frp", "size"), v_frp=("frp", "sum"), v_day=("day", "sum"), v_days=("date", "nunique"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--archive", default="../../archive/archive")
    ap.add_argument("--out", default="data/seq")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    files = sorted(glob.glob(os.path.join(a.archive, "modis_*.csv")) +
                   glob.glob(os.path.join(a.archive, "viirs-snpp_*.csv")))
    if not files:
        raise SystemExit(f"no archive files under {a.archive}")

    parts = {"modis": [], "viirs": []}
    daily, over = [], []
    t0 = time.time()
    for i, f in enumerate(files):
        sensor = "modis" if os.path.basename(f).startswith("modis") else "viirs"
        raw = read_one(f)
        if raw.empty:
            continue
        d = prep(raw, sensor)
        parts[sensor].append(agg_modis(d) if sensor == "modis" else agg_viirs(d))
        daily.append(d.groupby(["date", "sat"]).size().rename("n").reset_index())
        dd = d[d["day"] == 1]
        over.append(dd.groupby(["week", "sat"]).agg(lst_sum=("lst_h", "sum"), n=("lst_h", "size")).reset_index())
        print(f"[{i + 1}/{len(files)}] {os.path.basename(f)}: {len(raw):,} rows  ({time.time() - t0:.0f}s)",
              flush=True)

    # country files can split one cell: re-aggregate (sums add; max/conf handled; nunique days ~ approx max)
    m = pd.concat(parts["modis"]).groupby(level=[0, 1, 2]).agg(
        {c: ("max" if c in ("m_frp_max",) else "sum") for c in parts["modis"][0].columns})
    v = pd.concat(parts["viirs"]).groupby(level=[0, 1, 2]).agg(
        {c: "sum" for c in parts["viirs"][0].columns})
    cw = m.join(v, how="outer").fillna(0).reset_index()
    m_days_cap = 7
    cw["m_days"] = cw["m_days"].clip(upper=m_days_cap)
    cw["v_days"] = cw["v_days"].clip(upper=m_days_cap)
    for c in cw.columns:
        if c.endswith(("_n", "_terra", "_aqua", "_day", "_night", "_hi", "_lo", "_days")):
            cw[c] = cw[c].astype(np.int32)
        elif c not in ("lat_i", "lon_i", "week"):
            cw[c] = cw[c].astype(np.float32)
    cw.to_parquet(os.path.join(a.out, "cell_week.parquet"), index=False)

    sd = pd.concat(daily).groupby(["date", "sat"], as_index=False)["n"].sum()
    sd.to_parquet(os.path.join(a.out, "sensor_daily.parquet"), index=False)
    ov = pd.concat(over).groupby(["week", "sat"], as_index=False)[["lst_sum", "n"]].sum()
    ov["lst_mean_h"] = ov["lst_sum"] / ov["n"]
    ov.drop(columns="lst_sum").to_parquet(os.path.join(a.out, "overpass_week.parquet"), index=False)
    print(f"cell_week rows {len(cw):,}, cells {cw.groupby(['lat_i', 'lon_i']).ngroups:,}, "
          f"weeks {cw['week'].min()}..{cw['week'].max()}  ({time.time() - t0:.0f}s)")


if __name__ == "__main__":
    main()
