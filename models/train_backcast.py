"""Fit, validate and save Model 1 (backcast: MODIS -> VIIRS-equivalent per grid cell-month).

    python train_backcast.py [--config path] [--skip-validation]

Writes:
  artifacts/backcast.joblib              hybrid model retrained on all VIIRS years + metadata
  artifacts/combined_2002_2026.parquet   backcast (2002-2011) + observed VIIRS (2012+), `source` column
  reports/backcast_validation.{csv,md}   per-fold metrics for naive / GLM / hybrid / stacked
  reports/backcast_summary.md            fold means, % improvement vs naive, recommendation
  reports/backcast_region_bias.csv       annual-total bias per region
  reports/backcast_annual_totals.png     predicted vs actual annual totals by region
  reports/backcast_improvement.png       % improvement over the naive ratio
  reports/glm_conversion_factors.{csv,md} per-land-cover MODIS->VIIRS factors with 95% CIs
  reports/glm_coefficients.csv           every GLM term
"""
from __future__ import annotations

import argparse
import time

import numpy as np
import pandas as pd

from fire_model import artifacts
from fire_model.backcast import HybridBackcast, build_models
from fire_model.config import Config, load_config
from fire_model.data import load_harmonised
from fire_model.features import RF_NUMERIC
from fire_model.validate import (plot_annual_totals, plot_improvement, recommend, run_backcast_validation,
                                 summarise, to_markdown)


def validate_and_report(df: pd.DataFrame, cfg: Config) -> pd.DataFrame:
    rep = cfg.path("reports")
    rep.mkdir(parents=True, exist_ok=True)
    print("validation (year-blocked folds, backcast direction):")
    res = run_backcast_validation(df, cfg, lambda: build_models(cfg))
    m = res["metrics"]
    m.to_csv(rep / "backcast_validation.csv", index=False)
    (rep / "backcast_validation.md").write_text(to_markdown(m.drop(columns=[c for c in m if c.endswith("_vs_naive_pct")])))
    res["region_bias"].to_csv(rep / "backcast_region_bias.csv", index=False)
    res["annual_totals"].to_csv(rep / "backcast_annual_totals.csv", index=False)
    summary = summarise(m)
    choice, why = recommend(summary)
    notes = sorted({n for n in m["notes"] if n})
    (rep / "backcast_summary.md").write_text(
        f"# Backcast validation (target: VIIRS {cfg.target}, unit: grid cell-month)\n\n"
        "Mean over folds A (train 2017-2026, test 2012-2016) and B (train 2012-2016, test 2017-2026). "
        "`*_vs_naive_pct` = % improvement over the naive global ratio; `abs_region_bias_vs_naive_pp` = reduction in "
        "|annual region bias| in percentage points (positive = better in both).\n\n"
        + to_markdown(summary, ".2f") + f"\n**Recommendation:** {choice}. {why}\n\n"
        + ("**Notes:** " + "; ".join(notes) + "\n" if notes else ""))
    label = "VIIRS detections" if cfg.target == "count" else "VIIRS FRP (MW)"
    plot_annual_totals(res["annual_totals"], rep / "backcast_annual_totals.png", label)
    plot_improvement(summary, rep / "backcast_improvement.png")
    print("\n" + to_markdown(summary, ".2f") + f"\nRecommendation: {choice}. {why}")
    return summary


def fit_final(df: pd.DataFrame, cfg: Config) -> None:
    """Retrain the hybrid on all VIIRS years; backcast the MODIS-only years; write the combined series."""
    art = cfg.path("artifacts")
    rep = cfg.path("reports")
    rep.mkdir(parents=True, exist_ok=True)
    tc = cfg.target_col
    v0, v1 = cfg.viirs_years
    b0, b1 = cfg.backcast_years
    train = df[df["year"].between(v0, v1) & df[tc].notna()].reset_index(drop=True)
    y = train[tc].to_numpy(float)
    model: HybridBackcast = build_models(cfg, include_stacked=False)["hybrid"]
    print(f"\nfinal fit on {v0}-{v1}: {len(train):,} cell-months ...", flush=True)
    model.fit(train, y)

    cf = model.glm.conversion_factors()
    cf.to_csv(rep / "glm_conversion_factors.csv", index=False)
    (rep / "glm_conversion_factors.md").write_text(
        "# GLM MODIS->VIIRS conversion factors per land cover (95% CI)\n\n"
        "`slope`: elasticity of VIIRS on log1p(MODIS count). `ratio_at_10`: implied VIIRS:MODIS ratio at 10 MODIS "
        "detections (typical FRP, average region/month, pre-drift).\n"
        f"NB dispersion alpha = {model.glm.alpha:.3f} ({model.glm.alpha_method})\n\n" + to_markdown(cf))
    model.glm.coefficient_table().to_csv(rep / "glm_coefficients.csv", index=False)
    print(to_markdown(cf))

    back = df[df["year"].between(b0, b1)].reset_index(drop=True)
    p = model.predict(back)
    backcast = back[["cell_id", "lat", "lon", "region", "land_cover", "year", "month"]].assign(
        y=p["pred"].round(2), q05=p["q05"].round(2), q50=p["q50"].round(2), q95=p["q95"].round(2),
        p_any=model.glm.p_any(p["pred"].to_numpy()).round(4),
        extrapolated=p["extrapolated"], source="backcast")
    observed = train[["cell_id", "lat", "lon", "region", "land_cover", "year", "month"]].assign(
        y=y, q05=y, q50=y, q95=y, p_any=(y > 0).astype(float), extrapolated=False, source="observed")
    combined = pd.concat([backcast, observed], ignore_index=True).sort_values(["cell_id", "year", "month"],
                                                                                ignore_index=True)
    art.mkdir(parents=True, exist_ok=True)
    combined.to_parquet(art / "combined_2002_2026.parquet", index=False)
    print(f"backcast {b0}-{b1}: {len(backcast):,} cell-months, {backcast['extrapolated'].mean():.2%} extrapolated; "
          f"combined series {len(combined):,} rows")

    artifacts.save(art / "backcast.joblib", model, {
        "model": "hybrid (GLM + residual RF + QRF intervals + extrapolation guard)",
        "unit": f"grid cell ({cfg.raw['grid']['cell_deg']} deg) x month; not individual 375 m pixels",
        "target": cfg.target, "target_column": cfg.columns[tc],
        "training_years": [v0, v1], "backcast_years": [b0, b1], "n_train": len(train),
        "drift_cutoff_year": cfg.drift_cutoff,
        "glm_terms": model.glm.design.columns, "glm_dropped_terms": model.glm.design.dropped,
        "rf_features": list(model.rf.feature_names_in_), "rf_numeric": RF_NUMERIC,
        "land_cover_levels": model.glm.design.levels.land_cover, "region_levels": model.glm.design.levels.region,
        "nb_alpha": model.glm.alpha, "totals_calibration": None if model.smear.pooled is None else {
            "method": "ratio of sums per region x decile of log1p(mu), out-of-bag",
            "edges_log1p_mu": model.smear.edges.tolist(), "pooled": model.smear.pooled.tolist(),
            "per_region": {g: f.tolist() for g, f in model.smear.factors.items()}},
        "guard": {"percentile": model.guard_percentile, "limits_per_land_cover": model.limits},
        "quantiles": list(model.quantiles), "seed": cfg.seed,
    })


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config")
    ap.add_argument("--skip-validation", action="store_true")
    a = ap.parse_args()
    cfg = load_config(a.config)
    np.random.seed(cfg.seed)
    t0 = time.time()
    df = load_harmonised(cfg)
    print(f"loaded {len(df):,} cell-months, {df['cell_id'].nunique()} cells, target = VIIRS {cfg.target}")
    if not a.skip_validation:
        validate_and_report(df, cfg)
    fit_final(df, cfg)
    print(f"done in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
