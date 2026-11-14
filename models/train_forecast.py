"""Fit, validate (rolling origin) and save Model 2 (next-month forecast per grid cell-month).

    python train_forecast.py [--config path]

Needs artifacts/combined_2002_2026.parquet from train_backcast.py. Writes artifacts/forecast.joblib and
reports/forecast_validation.{csv,md}. Climate covariates are joined only if paths.climate is set.
"""
from __future__ import annotations

import argparse
import time

import numpy as np
import pandas as pd

from fire_model import artifacts
from fire_model.config import Config, load_config
from fire_model.data import load_climate
from fire_model.forecast import build_forecast_model, make_supervised
from fire_model.validate import forecast_metrics, to_markdown


def rolling_validation(sup: pd.DataFrame, cfg: Config) -> pd.DataFrame:
    """For each test year T: train on target years < T, test on T. Compare with seasonal climatology."""
    rows = []
    for T in cfg.raw["forecast"]["rolling_test_years"]:
        train, test = sup[sup["year"] < T], sup[sup["year"] == T]
        if train.empty or test.empty:
            continue
        assert train["year"].max() < test["year"].min()
        model = build_forecast_model(cfg).fit(train)
        p = model.predict(test)
        y = test["y"].to_numpy(float)
        for name, pred, pany in (("gbm_log1p+logit", p["expected"], p["p_fire"]),
                                 ("seasonal_climatology", p["clim"], p["clim_pany"])):
            m = forecast_metrics(y, pred.to_numpy(), pany.to_numpy())
            rows.append({"test_year": T, "train_years": f"{int(train['year'].min())}-{T - 1}", "model": name,
                         **m, "total_bias_pct": float((pred.sum() - y.sum()) / max(y.sum(), 1) * 100),
                         "n_test": len(test)})
        print(f"  test {T}: gbm dev={rows[-2]['poisson_dev']:.3f} auc={rows[-2]['auc']:.3f} | "
              f"climatology dev={rows[-1]['poisson_dev']:.3f} auc={rows[-1]['auc']:.3f}", flush=True)
    return pd.DataFrame(rows)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config")
    a = ap.parse_args()
    cfg = load_config(a.config)
    np.random.seed(cfg.seed)
    t0 = time.time()
    art, rep = cfg.path("artifacts"), cfg.path("reports")
    rep.mkdir(parents=True, exist_ok=True)
    series = pd.read_parquet(art / "combined_2002_2026.parquet")
    climate = load_climate(cfg)
    print(f"combined series: {len(series):,} rows; climate covariates: {'yes' if climate is not None else 'none'}")
    lags = list(cfg.raw["forecast"]["lags"])
    sup = make_supervised(series, lags, climate)

    print("rolling-origin validation:")
    res = rolling_validation(sup, cfg)
    res.to_csv(rep / "forecast_validation.csv", index=False)
    mean = res.groupby("model")[["poisson_dev", "mae", "rmse_log1p", "brier", "auc", "total_bias_pct"]].mean()
    mean = mean.reset_index()
    (rep / "forecast_validation.md").write_text(
        "# Forecast validation (rolling origin, next-month per grid cell)\n\n" + to_markdown(res)
        + "\n## Mean over test years\n\n" + to_markdown(mean))
    print("\n" + to_markdown(mean))

    model = build_forecast_model(cfg).fit(sup)
    artifacts.save(art / "forecast.joblib", model, {
        "model": "HistGradientBoosting (Poisson) + logistic P(fire)",
        "unit": "grid cell x month (VIIRS-equivalent)",
        "training_years": [int(sup["year"].min()), int(sup["year"].max())],
        "last_month": f"{int(series['year'].max())}-{int(series[series['year'] == series['year'].max()]['month'].max()):02d}",
        "lags": lags, "hgb_features": model.hgb_features, "climate_columns": model.climate_columns,
        "land_cover_levels": model.levels.land_cover, "region_levels": model.levels.region,
        "min_backcast_weight": model.min_backcast_weight, "n_train": len(sup), "seed": cfg.seed,
    })
    print(f"saved {art / 'forecast.joblib'} in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
