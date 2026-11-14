"""Baselines the LSTM has to beat, scored on dense held-out years (every cell x every valid week).

Usage (from models/):  .venv/bin/python -m seq.baselines

  ratio_global   VIIRS = r * MODIS, one r from the training years (the "simple scaling factor")
  ratio_rm       per (5 deg block x calendar month) ratio, shrunk to the global ratio, PLUS a per (block x month)
                 background rate for cell-weeks where MODIS saw nothing (the stand-in for per-biome x month;
                 no land-cover layer is joined yet)
  gbm            HistGradientBoosting, Poisson loss, on the same windowed lag features and training rows as
                 the LSTM

Predictions -> artifacts/seq/pred_<model>_<split>.npy  (C, n_weeks, 2) [count, frp];  weeks in weeks_<split>.npy
"""
import json
import os
import time

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

from seq.common import (ART, DATA, TRAIN, VAL, TEST, OOD, DYN, load_arrays, metrics, week_start)
from seq.windows import sample_rows, gather, flat_features, valid_weeks

SPLITS = {"val": VAL, "test": TEST, "ood": OOD}
SHRINK = 50.0     # pseudo-detections pulling a block-month ratio towards the global one


def month_of(weeks):
    return (week_start(weeks) + 3).astype("datetime64[M]").astype(int) % 12


def ratio_fit(R, Fm, Y, M, region, train_w):
    """Global and block x month ratios for count and FRP, plus block x month background when MODIS = 0."""
    out = {}
    m_cnt, m_frp = R[:, train_w], Fm[:, train_w]
    y = Y[:, train_w, :]
    out["r_global"] = np.array([y[..., 0].sum() / m_cnt.sum(), y[..., 1].sum() / m_frp.sum()])
    mon = month_of(train_w)
    key = region[:, None] * 12 + mon[None, :]
    df = pd.DataFrame({"key": key.ravel(), "m": m_cnt.ravel(), "mf": m_frp.ravel(), "y": y[..., 0].ravel(),
                       "yf": y[..., 1].ravel(), "zero": (m_cnt.ravel() == 0)})
    g = df.groupby("key")
    tot = g[["m", "mf", "y", "yf"]].sum()
    # split VIIRS into the part on MODIS>0 rows (ratio) and MODIS=0 rows (background rate per cell-week)
    z = df[df.zero].groupby("key").agg(yz=("y", "sum"), yfz=("yf", "sum"), nz=("y", "size"))
    tot = tot.join(z, how="left").fillna(0)
    rg = out["r_global"]
    tot["r"] = (tot.y - tot.yz + SHRINK * rg[0]) / (tot.m + SHRINK)
    tot["rf"] = (tot.yf - tot.yfz + SHRINK * rg[1] * 10) / (tot.mf + SHRINK * 10)
    tot["b"] = tot.yz / tot.nz.clip(lower=1)
    tot["bf"] = tot.yfz / tot.nz.clip(lower=1)
    out["table"] = tot[["r", "rf", "b", "bf"]]
    return out


def ratio_predict(fit, R, Fm, region, weeks, kind):
    m, mf = R[:, weeks], Fm[:, weeks]
    if kind == "global":
        return np.stack([fit["r_global"][0] * m, fit["r_global"][1] * mf], -1)
    key = region[:, None] * 12 + month_of(weeks)[None, :]
    t = fit["table"].reindex(np.unique(key))
    lut = {k: i for i, k in enumerate(t.index)}
    idx = np.vectorize(lut.get)(key)
    tv = t.to_numpy()
    tv[np.isnan(tv[:, 0]), 0] = fit["r_global"][0]
    tv[np.isnan(tv[:, 1]), 1] = fit["r_global"][1]
    tv = np.nan_to_num(tv)
    r, rf, b, bf = (tv[idx, j] for j in range(4))
    cnt = np.where(m > 0, r * m, b)
    frp = np.where(m > 0, rf * mf, bf)
    return np.stack([cnt, frp], -1).astype(np.float32)


def gbm_predict(models, X, S, C, weeks, chunk_weeks=26):
    out = np.zeros((C, len(weeks), 2), np.float32)
    for i in range(0, len(weeks), chunk_weeks):
        wk = weeks[i:i + chunk_weeks]
        cells = np.repeat(np.arange(C, dtype=np.int32), len(wk))
        ww = np.tile(wk, C).astype(np.int32)
        F = flat_features(*gather(X, S, cells, ww))
        for j, mdl in enumerate(models):
            out[:, i:i + len(wk), j] = mdl.predict(F).reshape(C, len(wk)) * (10.0 if j == 1 else 1.0)
    return out


def score(Y, P, region, weeks):
    res = {}
    C = Y.shape[0]
    reg = np.repeat(region, len(weeks))
    ww = np.tile(weeks, C)
    for j, name in enumerate(["count", "frp"]):
        y = Y[:, weeks, j].ravel()
        res[name] = metrics(y, P[..., j].ravel().astype(np.float64), reg, ww)
    return res


def main():
    os.makedirs(ART, exist_ok=True)
    rng = np.random.default_rng(42)
    X, Y, S, M, R = load_arrays(mmap=False)
    C, W = R.shape
    cells = pd.read_parquet(os.path.join(DATA, "cells.parquet"))
    region = cells["region"].to_numpy()
    Fm = np.expm1(X[:, :, DYN.index("m_frp")])

    train_w = valid_weeks(TRAIN, M, W)
    split_w = {k: valid_weeks(v, M, W) for k, v in SPLITS.items()}
    for k, w in split_w.items():
        np.save(os.path.join(ART, f"weeks_{k}.npy"), w)

    t0 = time.time()
    fit = ratio_fit(R, Fm, Y, M, region, train_w)
    print(f"global ratio count {fit['r_global'][0]:.3f}  frp {fit['r_global'][1]:.3f}")
    results = {}
    for k, w in split_w.items():
        for kind in ("global", "rm"):
            P = ratio_predict(fit, R, Fm, region, w, kind)
            np.save(os.path.join(ART, f"pred_ratio_{kind}_{k}.npy"), P)
            results.setdefault(f"ratio_{kind}", {})[k] = score(Y, P, region, w)
    print(f"ratios done ({time.time() - t0:.0f}s)")

    # ---- GBM on the shared training rows ----
    c_tr, w_tr, wt_tr, info = sample_rows(R, M, TRAIN, rng)
    np.savez(os.path.join(ART, "train_rows.npz"), cells=c_tr, weeks=w_tr, w=wt_tr)
    print("training rows:", info)
    F = flat_features(*gather(X, S, c_tr, w_tr))
    yc = Y[c_tr, w_tr, 0]
    yf = Y[c_tr, w_tr, 1] / 10.0
    models = []
    for name, y in (("count", yc), ("frp", yf)):
        t1 = time.time()
        m = HistGradientBoostingRegressor(loss="poisson", learning_rate=0.08, max_iter=400, max_leaf_nodes=63,
                                          min_samples_leaf=200, l2_regularization=1.0, early_stopping=False,
                                          random_state=42)
        m.fit(F, y, sample_weight=wt_tr)
        models.append(m)
        print(f"gbm {name} fitted ({time.time() - t1:.0f}s)")
    joblib.dump(models, os.path.join(ART, "gbm.joblib"))
    for k, w in split_w.items():
        P = gbm_predict(models, X, S, C, w)
        np.save(os.path.join(ART, f"pred_gbm_{k}.npy"), P)
        results.setdefault("gbm", {})[k] = score(Y, P, region, w)

    with open(os.path.join(ART, "baseline_metrics.json"), "w") as f:
        json.dump({"train_rows": info, "results": results}, f, indent=1)
    for mdl, r in results.items():
        t = r["test"]
        print(f"{mdl:14s} TEST count: dev {t['count']['poisson_dev']:.3f}  mae {t['count']['mae']:.3f}  "
              f"rm_r2log {t['count']['region_month_r2_log1p']:.3f}  bias {t['count']['total_bias_pct']:+.1f}% | "
              f"frp dev {t['frp']['poisson_dev']:.2f} bias {t['frp']['total_bias_pct']:+.1f}%")
    print(f"total {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
