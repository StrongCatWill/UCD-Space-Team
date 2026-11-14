"""cell_week.parquet (sparse) -> dense arrays for the sequence models.

Usage (from models/):  .venv/bin/python -m seq.dataset

Writes to data/seq/:
  dyn.npy        (C, W, 12) float32  log1p MODIS features + NA-wide Terra/Aqua coverage, per week
  target.npy     (C, W, 2)  float32  S-NPP count, S-NPP FRP sum (raw; NaN-free, gate with target_ok)
  target_ok.npy  (W,) bool           S-NPP reported on all 7 days (outage weeks are excluded from loss/eval)
  raw_mcount.npy (C, W) float32      raw MODIS count (for the ratio baselines)
  static.npy     (C, 6) float32      lat, lon, MODIS climatology (count, FRP per detection, night fraction), flag
  cells.parquet  cell index -> lat_i, lon_i, region (5 deg block)
  meta.json
"""
import json
import os

import numpy as np
import pandas as pd

from seq.common import DATA, DYN, STATIC, EPOCH, CELL_DEG, week_year


def main():
    cw = pd.read_parquet(os.path.join(DATA, "cell_week.parquet"))
    sd = pd.read_parquet(os.path.join(DATA, "sensor_daily.parquet"))
    W = int(cw["week"].max()) + 1

    cells = cw[["lat_i", "lon_i"]].drop_duplicates().sort_values(["lat_i", "lon_i"]).reset_index(drop=True)
    cells["cell"] = np.arange(len(cells), dtype=np.int32)
    cells["lat"] = (cells["lat_i"] + 0.5) * CELL_DEG
    cells["lon"] = (cells["lon_i"] + 0.5) * CELL_DEG
    cells["region"] = ((np.floor(cells["lat"] / 5).astype(int) + 90) * 1000 +
                       (np.floor(cells["lon"] / 5).astype(int) + 180)).astype(np.int32)
    cw = cw.merge(cells[["lat_i", "lon_i", "cell"]], on=["lat_i", "lon_i"])
    C = len(cells)
    c, w = cw["cell"].to_numpy(), cw["week"].to_numpy().astype(np.int64)
    print(f"cells {C:,}  weeks {W}  dense {C * W / 1e6:.1f}M cell-weeks")

    # coverage: fraction of the week's 7 days on which each satellite reported anything NA-wide
    sd["week"] = ((pd.to_datetime(sd["date"]).to_numpy().astype("datetime64[D]") - EPOCH).astype(np.int64) // 7)
    cov = sd.groupby(["week", "sat"])["date"].nunique().unstack(fill_value=0).reindex(range(W), fill_value=0) / 7.0
    cov_t, cov_a, cov_n = (cov.get(s, pd.Series(0.0, index=cov.index)).to_numpy(np.float32) for s in "TAN")
    target_ok = cov_n >= 1.0
    print(f"target weeks ok: {target_ok.sum()} (S-NPP full weeks), "
          f"Terra partial weeks {(cov_t[(cov_t > 0)] < 1).sum()}, Aqua partial {(cov_a[(cov_a > 0)] < 1).sum()}")

    X = np.lib.format.open_memmap(os.path.join(DATA, "dyn.npy"), mode="w+", dtype=np.float32,
                                  shape=(C, W, len(DYN)))
    X[:] = 0
    conf_mean = np.where(cw["m_n"] > 0, cw["m_conf_sum"] / cw["m_n"].clip(lower=1) / 100.0, 0.0)
    cols = {
        "m_n": np.log1p(cw["m_n"]), "m_terra": np.log1p(cw["m_terra"]), "m_aqua": np.log1p(cw["m_aqua"]),
        "m_night": np.log1p(cw["m_night"]), "m_hi": np.log1p(cw["m_hi"]), "m_lo": np.log1p(cw["m_lo"]),
        "m_frp": np.log1p(cw["m_frp"]), "m_frp_max": np.log1p(cw["m_frp_max"]), "m_conf": conf_mean,
        "m_days": cw["m_days"] / 7.0,
    }
    for j, name in enumerate(DYN):
        if name in cols:
            X[c, w, j] = np.asarray(cols[name], dtype=np.float32)
    X[:, :, DYN.index("cov_t")] = cov_t[None, :]
    X[:, :, DYN.index("cov_a")] = cov_a[None, :]
    X.flush()

    Y = np.lib.format.open_memmap(os.path.join(DATA, "target.npy"), mode="w+", dtype=np.float32, shape=(C, W, 2))
    Y[:] = 0
    Y[c, w, 0] = cw["v_n"].to_numpy(np.float32)
    Y[c, w, 1] = cw["v_frp"].to_numpy(np.float32)
    Y.flush()

    R = np.lib.format.open_memmap(os.path.join(DATA, "raw_mcount.npy"), mode="w+", dtype=np.float32, shape=(C, W))
    R[:] = 0
    R[c, w] = cw["m_n"].to_numpy(np.float32)
    R.flush()

    # static: MODIS-only climatology 2002-07..2022 (exists in both the training and the backcast period, so it
    # carries no VIIRS information). Fire size (FRP per detection) stands in for biome until land cover is joined.
    wy = week_year(cw["week"].to_numpy())
    clim = cw[(wy >= 2003) & (wy <= 2022)].groupby("cell")[["m_n", "m_frp", "m_night"]].sum()
    n_weeks = ((week_year(np.arange(W)) >= 2003) & (week_year(np.arange(W)) <= 2022)).sum()
    clim = clim.reindex(range(C), fill_value=0)
    has = (clim["m_n"] > 0).to_numpy()
    fpd = np.where(has, np.maximum(clim["m_frp"] / clim["m_n"].clip(lower=1), 1.0), np.nan)  # floor 1 MW
    med = np.nanmedian(fpd)
    S = np.zeros((C, len(STATIC)), np.float32)
    S[:, 0] = (cells["lat"] - 45) / 15
    S[:, 1] = (cells["lon"] + 100) / 30
    S[:, 2] = np.log1p(clim["m_n"].to_numpy() / n_weeks * 52)       # log1p(MODIS detections per year)
    S[:, 3] = np.log(np.where(has, fpd, med)) - np.log(med)
    S[:, 4] = np.where(has, clim["m_night"] / clim["m_n"].clip(lower=1), 0)
    S[:, 5] = has
    np.save(os.path.join(DATA, "static.npy"), S)
    np.save(os.path.join(DATA, "target_ok.npy"), target_ok)

    cells.to_parquet(os.path.join(DATA, "cells.parquet"), index=False)
    with open(os.path.join(DATA, "meta.json"), "w") as f:
        json.dump({"C": C, "W": W, "dyn": DYN, "static": STATIC, "cell_deg": CELL_DEG,
                   "epoch": str(EPOCH), "cells_with_modis": int(has.sum())}, f, indent=1)
    print(f"done. cells with any MODIS 2003-2022: {has.sum():,}/{C:,}")


if __name__ == "__main__":
    main()
