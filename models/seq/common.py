"""Shared settings and helpers for the sequence backcast. Pure numpy/pandas so both venvs can import it
(.venv: pandas 3 + lightgbm; .venv-torch: Python 3.9 + torch 2.2.2, the last torch with Intel-mac wheels)."""
import json
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.dirname(HERE)
DATA = os.path.join(MODELS, "data", "seq")
ART = os.path.join(MODELS, "artifacts", "seq")
REP = os.path.join(MODELS, "reports", "seq")

EPOCH = np.datetime64("2000-01-03")      # Monday; week w covers EPOCH + 7w .. +6
CELL_DEG = 0.25
LOOKBACK = 8                             # weeks of MODIS up to and including the target week (56 days)
LOOKAHEAD = 2                            # weeks after the target: legitimate in a backcast (MODIS is complete)
WIN = LOOKBACK + LOOKAHEAD

# Year splits by week year (year of the week's Thursday). Never random: neighbouring weeks are correlated.
TRAIN = (2012, 2017)
VAL = (2018, 2018)                       # early stopping / model choice only
TEST = (2019, 2022)                      # the honest held-out number
OOD = (2023, 2025)                       # reported only: MODIS C6.1 "61.03" processing from 2023, orbit drift 2024+
FINAL_TRAIN = (2012, 2022)               # all MODIS C6.2 + pre-drift years; matches the 2002-2011 inputs
BACKCAST_START = np.datetime64("2002-07-08")   # first full week with Aqua (Terra-only 2000-11..2002-06 not done)
BACKCAST_END_YEAR = 2011

DYN = ["m_n", "m_terra", "m_aqua", "m_night", "m_hi", "m_lo", "m_frp", "m_frp_max", "m_conf", "m_days",
       "cov_t", "cov_a"]
STATIC = ["lat", "lon", "clim_n", "clim_frp_per_det", "clim_night_frac", "has_modis"]
TARGETS = ["v_n", "v_frp"]


def week_start(w):
    return EPOCH + np.asarray(w, dtype="int64") * 7


def week_year(w):
    return (week_start(w) + 3).astype("datetime64[Y]").astype(int) + 1970


def week_doy(w):
    d = week_start(w) + 3
    return (d - d.astype("datetime64[Y]")).astype(int)


def weeks_in(years, n_weeks):
    wy = week_year(np.arange(n_weeks))
    return np.where((wy >= years[0]) & (wy <= years[1]))[0]


def load_meta():
    with open(os.path.join(DATA, "meta.json")) as f:
        return json.load(f)


def load_arrays(mmap=True):
    mode = "r" if mmap else None
    X = np.load(os.path.join(DATA, "dyn.npy"), mmap_mode=mode)       # (C, W, len(DYN)) float32, scaled
    Y = np.load(os.path.join(DATA, "target.npy"), mmap_mode=mode)    # (C, W, 2) float32 raw count, frp
    S = np.load(os.path.join(DATA, "static.npy"))                    # (C, len(STATIC)) float32, scaled
    M = np.load(os.path.join(DATA, "target_ok.npy"))                 # (W,) bool: S-NPP had all 7 days
    R = np.load(os.path.join(DATA, "raw_mcount.npy"), mmap_mode=mode)  # (C, W) raw MODIS count (for ratios)
    return X, Y, S, M, R


def time_features(weeks):
    doy = week_doy(weeks)
    a = 2 * np.pi * doy / 365.25
    return np.stack([np.sin(a), np.cos(a)], axis=-1).astype(np.float32)


# ---------------- metrics (numpy only) ----------------

def poisson_deviance(y, mu, w=None):
    mu = np.maximum(mu, 1e-8)
    term = np.where(y > 0, y * np.log(np.maximum(y, 1e-8) / mu), 0.0) - (y - mu)
    d = 2 * term
    return float(np.average(d, weights=w))


def metrics(y, mu, cells_region=None, weeks=None):
    """y, mu: 1-D arrays over the same dense evaluation rows."""
    out = {
        "poisson_dev": poisson_deviance(y, mu),
        "mae": float(np.mean(np.abs(y - mu))),
        "rmse_log1p": float(np.sqrt(np.mean((np.log1p(y) - np.log1p(mu)) ** 2))),
        "total_bias_pct": float(100 * (mu.sum() - y.sum()) / max(y.sum(), 1e-8)),
    }
    act = (y > 0) | (mu > 0.5)
    out["mae_active"] = float(np.mean(np.abs(y[act] - mu[act]))) if act.any() else float("nan")
    if cells_region is not None and weeks is not None:
        # calendar-scale skill: totals per (5 deg block, month)
        month = week_start(weeks).astype("datetime64[M]").astype(int)
        key = cells_region.astype(np.int64) * 100000 + month
        uk, inv = np.unique(key, return_inverse=True)
        ty = np.bincount(inv, weights=y, minlength=len(uk))
        tm = np.bincount(inv, weights=mu, minlength=len(uk))
        keep = (ty > 0) | (tm > 0)
        ty, tm = ty[keep], tm[keep]
        out["region_month_mae"] = float(np.mean(np.abs(ty - tm)))
        out["region_month_rmse_log1p"] = float(np.sqrt(np.mean((np.log1p(ty) - np.log1p(tm)) ** 2)))
        out["region_month_r2_log1p"] = float(1 - np.sum((np.log1p(ty) - np.log1p(tm)) ** 2) /
                                             np.sum((np.log1p(ty) - np.log1p(ty).mean()) ** 2))
    return out
