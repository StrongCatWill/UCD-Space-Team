"""Model 2 - next-month fire-activity forecast per grid cell (no MODIS inputs).

Unit of analysis: one grid cell per month (VIIRS-equivalent scale, from Model 1's combined 2002-2026 series).
Inputs: lagged activity (1, 2, 3, 12 months), per-cell seasonal climatology (from training rows only),
land cover, region, month, and optional climate covariates (pluggable; the model runs without them).
Targets: expected activity and P(any fire) (logistic regression).
Expected activity: HistGradientBoosting with squared-error loss on z = log1p(y), back-transformed as
exp(z_hat) * S - 1 with a totals calibration S per region x decile of z_hat (ratio of sums, as in the
backcast) from out-of-fold predictions (2 folds grouped by cell) of observed rows only. Duan smearing matched
the typical cell-month and left totals 17% low. (Poisson loss diverged on real data: where a large fire
meets a near-zero prediction, the Newton step G/(H + l2) explodes - raw log predictions reached ~233.)
Backcast rows are down-weighted by their relative interval width so uncertain years count less.
For P(fire), backcast rows use their model probability p_any = P(y > 0) as a soft label (their y is an
expected value, so y > 0 is not a valid "fire" label).
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from .backcast import TotalsCalibration
from .features import Levels, add_lags, climatology, join_climatology


def sample_weights(df: pd.DataFrame, min_weight: float) -> np.ndarray:
    """Observed rows: 1. Backcast rows: 1 / (1 + (q95 - q05) / (q50 + 1)), clipped to [min_weight, 1]."""
    rel = (df["q95"] - df["q05"]) / (df["q50"] + 1)
    w = np.clip(1.0 / (1.0 + rel.to_numpy(float)), min_weight, 1.0)
    return np.where(df["source"].to_numpy() == "backcast", w, 1.0)


def make_supervised(series: pd.DataFrame, lags: list[int], climate: pd.DataFrame | None = None) -> pd.DataFrame:
    """One row per cell-month target with its lag features; rows lacking any lag are dropped."""
    s = add_lags(series, lags)
    if climate is not None:
        s = s.merge(climate, on=["cell_id", "year", "month"], how="left")
    return s.dropna(subset=[f"lag_{k}" for k in lags]).reset_index(drop=True)


@dataclass
class ForecastModel:
    lags: list[int] = field(default_factory=lambda: [1, 2, 3, 12])
    hgb_params: dict = field(default_factory=dict)
    min_backcast_weight: float = 0.1
    climate_columns: list[str] = field(default_factory=list)
    seed: int = 42
    levels: Levels | None = None
    clim_cell: pd.DataFrame | None = None
    clim_region: pd.DataFrame | None = None
    reg: HistGradientBoostingRegressor | None = None
    smear: TotalsCalibration = field(default_factory=TotalsCalibration)
    clf: object | None = None
    hgb_features: list[str] = field(default_factory=list)
    logit_features: list[str] = field(default_factory=list)

    def _prep(self, sup: pd.DataFrame) -> pd.DataFrame:
        f = join_climatology(sup.drop(columns=["clim", "clim_pany"], errors="ignore"),
                             self.clim_cell, self.clim_region)
        for k in self.lags:
            f[f"log_lag_{k}"] = np.log1p(f[f"lag_{k}"].astype(float))
        f["log_clim"] = np.log1p(f["clim"])
        m = f["month"].astype(float)
        f["month_sin"], f["month_cos"] = np.sin(2 * np.pi * m / 12), np.cos(2 * np.pi * m / 12)
        lc_code = {v: i for i, v in enumerate(self.levels.land_cover)}
        rg_code = {v: i for i, v in enumerate(self.levels.region)}
        f["lc_code"] = f["land_cover"].map(lc_code).astype(float)   # unseen -> NaN (treated as missing)
        f["region_code"] = f["region"].map(rg_code).astype(float)
        for c in self.climate_columns:
            if c not in f:
                f[c] = np.nan
        return f

    def _hgb_X(self, f: pd.DataFrame) -> pd.DataFrame:
        return f[self.hgb_features].astype(float)

    def _logit_X(self, f: pd.DataFrame) -> np.ndarray:
        num = f[[f"log_lag_{k}" for k in self.lags] + ["log_clim", "clim_pany", "month_sin", "month_cos"]]
        lc = np.column_stack([(f["land_cover"] == v).to_numpy(float) for v in self.levels.land_cover])
        rg = np.column_stack([(f["region"] == v).to_numpy(float) for v in self.levels.region])
        return np.column_stack([num.to_numpy(float), lc, rg])

    def fit(self, sup: pd.DataFrame) -> "ForecastModel":
        self.levels = Levels.from_frame(sup)
        self.clim_cell, self.clim_region = climatology(sup)   # training rows only
        f = self._prep(sup)
        self.hgb_features = ([f"log_lag_{k}" for k in self.lags] + ["log_clim", "clim_pany", "month",
                             "lc_code", "region_code"] + list(self.climate_columns))
        w = sample_weights(f, self.min_backcast_weight)
        y = f["y"].to_numpy(float)
        X, z = self._hgb_X(f), np.log1p(y)
        # totals calibration from out-of-fold predictions: 2 folds grouped by cell, observed rows only
        cells = f["cell_id"].astype(str).to_numpy()
        fold = (pd.util.hash_array(cells.astype(object)) % 2).astype(int)
        obs = f["source"].to_numpy() == "observed" if "source" in f else np.ones(len(f), bool)
        region = f["region"].to_numpy(str)
        grp, key, yy, base = [], [], [], []
        for k in (0, 1):
            tr, te = fold != k, (fold == k) & obs
            if tr.any() and te.any():
                zh = self._new_reg().fit(X[tr], z[tr], sample_weight=w[tr]).predict(X[te])
                grp.append(region[te]); key.append(zh); yy.append(y[te]); base.append(np.expm1(zh))
        self.smear = (TotalsCalibration().fit(*(np.concatenate(a) for a in (grp, key, yy, base)))
                      if key else TotalsCalibration())
        self.reg = self._new_reg().fit(X, z, sample_weight=w)
        # soft labels: observed rows are 0/1; a backcast row with P(any fire) = p enters twice,
        # as label 1 with weight p*w and label 0 with weight (1-p)*w
        p = f["p_any"].to_numpy(float) if "p_any" in f else (y > 0).astype(float)
        Xl = self._logit_X(f)
        self.clf = make_pipeline(StandardScaler(), LogisticRegression(max_iter=2000, C=1.0))
        self.clf.fit(np.vstack([Xl, Xl]), np.r_[np.ones(len(y)), np.zeros(len(y))].astype(int),
                     logisticregression__sample_weight=np.r_[p * w, (1 - p) * w])
        return self

    def _new_reg(self) -> HistGradientBoostingRegressor:
        cat = [c in ("month", "lc_code", "region_code") for c in self.hgb_features]
        return HistGradientBoostingRegressor(loss="squared_error", categorical_features=cat,
                                             random_state=self.seed, **self.hgb_params)

    def expected(self, f: pd.DataFrame) -> np.ndarray:
        zh = self.reg.predict(self._hgb_X(f))
        return np.maximum(np.exp(zh) * self.smear(f["region"].to_numpy(str), zh) - 1, 0)

    def predict(self, sup: pd.DataFrame) -> pd.DataFrame:
        f = self._prep(sup)
        return pd.DataFrame({"expected": self.expected(f),
                             "p_fire": self.clf.predict_proba(self._logit_X(f))[:, 1],
                             "clim": f["clim"].to_numpy(), "clim_pany": f["clim_pany"].to_numpy()},
                            index=sup.index)


def query_rows(series: pd.DataFrame, cell_ids: list[str], year: int, month: int, lags: list[int],
               climate: pd.DataFrame | None = None) -> pd.DataFrame:
    """Feature rows for forecasting `year-month` for the given cells, with lags looked up in `series`."""
    info = series.drop_duplicates("cell_id").set_index("cell_id")[["region", "land_cover"]]
    q = pd.DataFrame({"cell_id": cell_ids, "year": year, "month": month})
    q = q.join(info, on="cell_id")
    q = add_lags(q, lags, history=series)
    if climate is not None:
        q = q.merge(climate, on=["cell_id", "year", "month"], how="left")
    return q


def build_forecast_model(cfg) -> ForecastModel:
    fc = cfg.section("forecast")
    return ForecastModel(lags=list(fc["lags"]), hgb_params=fc["hgb"], min_backcast_weight=fc["min_backcast_weight"],
                         climate_columns=list(fc.get("climate_columns") or []) if cfg.path("climate") else [],
                         seed=cfg.seed)
