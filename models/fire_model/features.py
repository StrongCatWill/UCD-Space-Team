"""Feature builders for the backcast (Model 1) and the forecast (Model 2).

Unit of analysis: one grid cell per month. Category levels are learned on training data and stored, so
prediction-time frames get exactly the same columns (unseen levels fall back to the reference level).
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

MONTHS = list(range(1, 13))


def add_backcast_features(df: pd.DataFrame, drift_cutoff: int) -> pd.DataFrame:
    """MODIS-derived features. No year term or time trend: those can't be estimated for 2002-2011."""
    out = df.copy()
    cnt = out["modis_count"].to_numpy(dtype=float)
    safe = np.maximum(cnt, 1.0)
    out["log_cnt"] = np.log1p(cnt)
    out["log_frp"] = np.log1p(out["modis_frp_sum"].to_numpy(dtype=float))
    out["log_frp_max"] = np.log1p(out["modis_frp_max"].to_numpy(dtype=float))
    out["hc_share"] = np.where(cnt > 0, out["modis_count_high_conf"] / safe, 0.0)
    out["aqua_share"] = np.where(cnt > 0, out["modis_count_aqua"] / safe, 0.0)
    out["drift"] = (out["year"] >= drift_cutoff).astype(float)
    return out


@dataclass
class Levels:
    """Category levels seen in training (first = reference level)."""
    land_cover: list[str] = field(default_factory=list)
    region: list[str] = field(default_factory=list)

    @classmethod
    def from_frame(cls, df: pd.DataFrame) -> "Levels":
        return cls(sorted(df["land_cover"].unique().tolist()), sorted(df["region"].unique().tolist()))


def _onehot(values: pd.Series, levels: list[str], prefix: str, drop_first: bool) -> pd.DataFrame:
    use = levels[1:] if drop_first else levels
    v = values.to_numpy()
    return pd.DataFrame({f"{prefix}[{lv}]": (v == lv).astype(float) for lv in use}, index=values.index)


@dataclass
class GlmDesign:
    """Design matrix for the GLM:
    const + log1p(frp) + land cover + region + month + drift
          + land cover x 1{MODIS count > 0} + land cover x log1p(MODIS count) (one slope per class).

    The any-detection indicator lets the 0 -> 1 detection jump differ from the slope over positive counts;
    without it (91%+ zero cell-months) the single slope overshoots badly on large fires.

    Columns that are constant zero in training (e.g. drift when every training year is pre-cut-off) are
    dropped and listed in `dropped`.
    """
    levels: Levels = field(default_factory=Levels)
    columns: list[str] = field(default_factory=list)
    dropped: list[str] = field(default_factory=list)

    def _full(self, f: pd.DataFrame) -> pd.DataFrame:
        parts = [
            pd.DataFrame({"const": 1.0, "log_frp": f["log_frp"]}, index=f.index),
            _onehot(f["land_cover"], self.levels.land_cover, "lc", drop_first=True),
            _onehot(f["region"], self.levels.region, "region", drop_first=True),
            _onehot(f["month"].astype(int).astype(str), [str(m) for m in MONTHS], "month", drop_first=True),
            pd.DataFrame({"drift": f["drift"]}, index=f.index),
        ]
        lc = f["land_cover"].to_numpy()
        anym = (f["log_cnt"].to_numpy() > 0).astype(float)
        parts.append(pd.DataFrame({f"any[{lv}]": np.where(lc == lv, anym, 0.0)
                                   for lv in self.levels.land_cover}, index=f.index))
        parts.append(pd.DataFrame({f"slope[{lv}]": np.where(lc == lv, f["log_cnt"], 0.0)
                                   for lv in self.levels.land_cover}, index=f.index))
        return pd.concat(parts, axis=1)

    def fit(self, f: pd.DataFrame) -> "GlmDesign":
        self.levels = Levels.from_frame(f)
        full = self._full(f)
        const_zero = [c for c in full.columns if c != "const" and full[c].abs().sum() == 0]
        constant = [c for c in full.columns if c != "const" and c not in const_zero and full[c].nunique() == 1]
        self.dropped = const_zero + constant
        self.columns = [c for c in full.columns if c not in self.dropped]
        return self

    def transform(self, f: pd.DataFrame) -> pd.DataFrame:
        return self._full(f)[self.columns]


RF_NUMERIC = ["log_cnt", "log_frp", "log_frp_max", "hc_share", "aqua_share", "drift", "month_sin", "month_cos"]


def rf_matrix(f: pd.DataFrame, levels: Levels, extra: dict[str, np.ndarray] | None = None) -> pd.DataFrame:
    """Numeric matrix for tree models: MODIS features, cyclic month, one-hot land cover/region (+ extras)."""
    m = f["month"].to_numpy(dtype=float)
    X = pd.DataFrame({c: f[c].to_numpy(dtype=float) for c in RF_NUMERIC[:6]}, index=f.index)
    X["month_sin"] = np.sin(2 * np.pi * m / 12)
    X["month_cos"] = np.cos(2 * np.pi * m / 12)
    X = pd.concat([X, _onehot(f["land_cover"], levels.land_cover, "lc", False),
                   _onehot(f["region"], levels.region, "region", False)], axis=1)
    for k, v in (extra or {}).items():
        X[k] = v
    return X


# ---------------------------------------------------------------- forecast (Model 2)

def add_time_index(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    out["t"] = out["year"].astype(int) * 12 + out["month"].astype(int) - 1
    return out


def add_lags(rows: pd.DataFrame, lags: list[int], value: str = "y",
             history: pd.DataFrame | None = None) -> pd.DataFrame:
    """lag_k = `value` of the same cell k months before each row (NaN if that month is absent).
    Lags are looked up in `history` (default: `rows` itself)."""
    s = add_time_index(rows)
    h = add_time_index(rows if history is None else history)[["cell_id", "t", value]]
    for k in lags:
        shifted = h.assign(t=h["t"] + k).rename(columns={value: f"lag_{k}"})
        s = s.merge(shifted, on=["cell_id", "t"], how="left")
    return s


def climatology(train: pd.DataFrame, value: str = "y") -> tuple[pd.DataFrame, pd.DataFrame]:
    """Per cell x calendar month mean activity and P(any fire), from training rows only;
    plus a region x month fallback for cells without history. P(any fire) averages the `p_any` column
    (backcast rows hold an expected value, so y > 0 would wrongly call almost every backcast month a fire)."""
    t = train.assign(_pany=train["p_any"] if "p_any" in train else (train[value] > 0).astype(float))
    cell = (t.groupby(["cell_id", "month"])
            .agg(clim=(value, "mean"), clim_pany=("_pany", "mean")).reset_index())
    region = (t.groupby(["region", "month"])
              .agg(clim_r=(value, "mean"), clim_pany_r=("_pany", "mean")).reset_index())
    return cell, region


def join_climatology(df: pd.DataFrame, cell: pd.DataFrame, region: pd.DataFrame) -> pd.DataFrame:
    out = df.merge(cell, on=["cell_id", "month"], how="left").merge(region, on=["region", "month"], how="left")
    out["clim"] = out["clim"].fillna(out["clim_r"]).fillna(0.0)
    out["clim_pany"] = out["clim_pany"].fillna(out["clim_pany_r"]).fillna(0.0)
    return out.drop(columns=["clim_r", "clim_pany_r"])
