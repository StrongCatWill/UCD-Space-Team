"""Generate a realistic fake harmonised cell-month table so the pipeline runs before real data exists.

Unit: one 0.5 deg grid cell per month. Many zeros, strong seasonality, land-cover-dependent VIIRS:MODIS
ratio (non-linear at high counts), Terra/Aqua mix (Aqua from 2002-07), MODIS orbit drift from 2021
(Terra loses detections), VIIRS (S-NPP) present only 2012+, and some extreme early-2000s fire years that
exceed anything in 2012-2026 (to exercise the extrapolation guard).

    python make_synthetic.py [--out data/harmonised.parquet] [--cells 400] [--seed 42]
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from fire_model.config import Config, load_config

REGIONS = {  # name: (lon range, lat range, peak month, season width, base activity)
    "US_West": ((-124, -104), (32, 49), 8, 1.6, 2.0),
    "US_Southeast": ((-95, -76), (25, 37), 3, 2.0, 2.5),
    "Canada_West": ((-130, -110), (49, 60), 7, 1.3, 1.5),
    "Canada_Boreal": ((-110, -70), (50, 60), 6, 1.2, 1.2),
    "Mexico": ((-110, -88), (15, 30), 4, 1.4, 3.0),
}
LAND_COVER = ["forest", "shrubland", "grassland", "cropland", "savanna", "wetland"]
LC_PROBS = {
    "US_West": [.35, .35, .15, .1, .03, .02], "US_Southeast": [.4, .05, .15, .3, .05, .05],
    "Canada_West": [.7, .1, .1, .05, 0, .05], "Canada_Boreal": [.7, .05, .05, .05, 0, .15],
    "Mexico": [.25, .2, .15, .2, .2, 0],
}
# true VIIRS:MODIS detection ratio per land cover (small/cool fires -> VIIRS sees relatively more)
RATIO = {"forest": 2.2, "shrubland": 2.6, "grassland": 3.4, "cropland": 4.5, "savanna": 3.0, "wetland": 2.4}
FIRE_SIZE = {"forest": 4.0, "shrubland": 2.5, "grassland": 1.5, "cropland": 0.8, "savanna": 1.8, "wetland": 1.2}
EXTREME_YEARS = {("Mexico", 2003): 6.0, ("Canada_West", 2004): 5.0, ("US_West", 2002): 4.0,
                 ("Canada_Boreal", 2023): 3.0, ("US_West", 2020): 3.0}


def make(cfg: Config, n_cells: int | None = None, seed: int | None = None) -> pd.DataFrame:
    syn = cfg.section("synthetic")
    rng = np.random.default_rng(cfg.seed if seed is None else seed)
    n_cells = n_cells or int(syn["n_cells"])
    y0, y1 = syn["years"]
    v0, v1 = cfg.viirs_years
    deg = float(cfg.raw["grid"]["cell_deg"])

    regions = rng.choice(list(REGIONS), size=n_cells)
    cells = []
    seen = set()
    for i, r in enumerate(regions):
        (lo0, lo1), (la0, la1), *_ = REGIONS[r]
        while True:
            lat = np.floor(rng.uniform(la0, la1) / deg) * deg + deg / 2
            lon = np.floor(rng.uniform(lo0, lo1) / deg) * deg + deg / 2
            if (lat, lon) not in seen:
                seen.add((lat, lon))
                break
        cells.append((f"{lat:.2f}_{lon:.2f}", lat, lon, r, rng.choice(LAND_COVER, p=LC_PROBS[r]),
                      rng.lognormal(0, 1.0)))
    cell_df = pd.DataFrame(cells, columns=["cell_id", "lat", "lon", "region", "land_cover", "cell_base"])

    months = pd.DataFrame([(y, m) for y in range(y0, y1 + 1) for m in range(1, 13)
                           if not (y == y1 and m > syn["last_month"])], columns=["year", "month"])
    df = cell_df.merge(months, how="cross")
    n = len(df)

    region_year = {(r, y): rng.lognormal(0, 0.35) * EXTREME_YEARS.get((r, y), 1.0)
                   for r in REGIONS for y in range(y0, y1 + 1)}
    peak = df["region"].map({k: v[2] for k, v in REGIONS.items()}).to_numpy()
    width = df["region"].map({k: v[3] for k, v in REGIONS.items()}).to_numpy()
    base = df["region"].map({k: v[4] for k, v in REGIONS.items()}).to_numpy()
    dist = np.minimum(np.abs(df["month"] - peak), 12 - np.abs(df["month"] - peak))
    season = np.exp(-0.5 * (dist / width) ** 2)
    ry = np.array([region_year[(r, y)] for r, y in zip(df["region"], df["year"])])

    # latent fire activity, zero-inflated (most cell-months have no fire)
    lam = base * df["cell_base"].to_numpy() * season * ry * rng.gamma(0.6, 1 / 0.6, n)
    active = rng.random(n) < np.clip(0.08 + 0.6 * season * np.minimum(ry, 2) / 2, 0, 0.9)
    lam = np.where(active, lam, 0.0)
    size = df["land_cover"].map(FIRE_SIZE).to_numpy()

    # MODIS: Terra + Aqua (Aqua starts 2002-07); drift era -> Terra detects less, mix shifts to Aqua
    aqua_on = (df["year"] > 2002) | (df["month"] >= 7)
    drift = df["year"] >= cfg.drift_cutoff
    terra_eff = np.where(drift, 0.65, 1.0)
    modis_lam = lam * size
    terra = rng.poisson(modis_lam * 0.5 * terra_eff)
    aqua = np.where(aqua_on, rng.poisson(modis_lam * 0.5), 0)
    modis = terra + aqua
    high_conf = rng.binomial(modis, np.clip(0.2 + 0.05 * size, 0, 0.9))
    frp_per = rng.gamma(2.0, 10.0 * size / 2, n)
    modis_frp = np.round(modis * frp_per, 1)
    modis_frp_max = np.round(np.where(modis > 0, frp_per * (1 + rng.exponential(1.5, n)), 0.0), 1)

    # VIIRS S-NPP: land-cover ratio x truth, saturating at high counts, plus some fires MODIS misses
    ratio = df["land_cover"].map(RATIO).to_numpy()
    sat = 1.0 + 0.6 * np.exp(-modis_lam / 15.0)
    viirs_lam = lam * size * ratio * sat * (1 + 0.15 * (high_conf / np.maximum(modis, 1))) + 0.05 * season
    viirs = rng.negative_binomial(2.0, 2.0 / (2.0 + viirs_lam)).astype(float)
    viirs_frp = np.round(viirs * rng.gamma(2.0, 3.0 * size / 2, n), 1)
    no_viirs = ~df["year"].between(v0, v1)
    viirs[no_viirs] = np.nan
    viirs_frp[no_viirs] = np.nan

    out = pd.DataFrame({
        "cell_id": df["cell_id"], "lat": df["lat"], "lon": df["lon"], "region": df["region"],
        "land_cover": df["land_cover"], "year": df["year"], "month": df["month"],
        "modis_count": modis, "modis_count_terra": terra, "modis_count_aqua": aqua,
        "modis_count_high_conf": high_conf, "modis_frp_sum": modis_frp, "modis_frp_max": modis_frp_max,
        "viirs_count": viirs, "viirs_frp_sum": viirs_frp,
    })
    # write with the configured (teammate) column names
    return out.rename(columns=cfg.columns)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config")
    ap.add_argument("--out")
    ap.add_argument("--cells", type=int)
    ap.add_argument("--seed", type=int)
    a = ap.parse_args()
    cfg = load_config(a.config)
    out = Path(a.out) if a.out else cfg.path("data")
    out.parent.mkdir(parents=True, exist_ok=True)
    df = make(cfg, a.cells, a.seed)
    df.to_parquet(out, index=False) if out.suffix == ".parquet" else df.to_csv(out, index=False)
    vc = cfg.columns["viirs_count"]
    print(f"wrote {out}: {len(df):,} rows, {df[cfg.columns['cell_id']].nunique()} cells, "
          f"{(df[cfg.columns['modis_count']] == 0).mean():.0%} zero MODIS cell-months, "
          f"VIIRS non-null {df[vc].notna().sum():,}")


if __name__ == "__main__":
    main()
