# Fire-activity prediction models

Two separate models for the NASA Space Apps 2026 fire-activity calendar:

1. **Backcast**: translates MODIS-only years (2002–2011) into **VIIRS-equivalent** activity, so the
   25-year record has one scale.
2. **Forecast**: predicts next-month activity and P(any fire) from the combined 2002–2026 series. It uses no
   MODIS inputs.

> **Unit of analysis: one grid cell (default 0.5°) per calendar month.** These models do **not** recreate
> individual 375 m VIIRS pixels. "VIIRS-equivalent" means the cell-month total (detection count, or summed
> FRP) that VIIRS would have reported for that cell.

## Quick start

```sh
cd models
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
source .venv/bin/activate

python make_synthetic.py      # fake harmonised table → data/harmonised.parquet (until real data arrives)
python train_backcast.py      # validate + fit Model 1 → artifacts/, reports/   (~5 min on synthetic)
python train_forecast.py      # validate + fit Model 2 → artifacts/, reports/   (~1 min)
pytest                        # 25 tests, ~30 s, own tmp data
uvicorn api:app --reload      # http://127.0.0.1:8000/docs
```

To switch to real data, point `paths.data` in `fire_model/config.yaml` at the harmonised table
(`.parquet` or `.csv`). Map its column names under `columns:` (only the right-hand side needs editing).
`data.py` stops with a message that names every missing column and every broken invariant.

## Input schema (one row per `cell_id × year × month`, dense: zero months included)

| canonical | type | meaning |
|---|---|---|
| `cell_id`, `lat`, `lon` | str, float | grid cell and its centre |
| `region`, `land_cover` | str | categories (levels learned from training data) |
| `year`, `month` | int | |
| `modis_count`, `modis_count_terra`, `modis_count_aqua` | int | MODIS detections (terra + aqua = total) |
| `modis_count_high_conf` | int | high-confidence MODIS detections |
| `modis_frp_sum`, `modis_frp_max` | float | MODIS fire radiative power, MW |
| `viirs_count`, `viirs_frp_sum` | float, nullable | **VIIRS S-NPP** target. **null = no coverage (before 2012); 0 = observed, no fire** |

The default target is **S-NPP only**. It is the one VIIRS satellite that covers all of 2012–2026. Summing
S-NPP + NOAA-20 + NOAA-21 would create step changes in 2018 and 2024 that the model would learn as
"activity". Cells with no detection ever are dropped (`grid.keep_cells_with_any_detection`).

## Model 1: backcast (`fire_model/backcast.py`)

| step | what |
|---|---|
| GLM | negative binomial, log link, **dispersion α estimated by MLE** (Tweedie for `target: frp`). Terms: log1p(MODIS FRP), land cover, region, month, drift era, and **per land cover**: a slope on log1p(MODIS count) plus a 0→1-detection jump |
| residual RF | random forest on `r = log((y+1)/(μ+1))`; prediction `(μ+1)·exp(r̂)·S − 1`, clipped at 0 |
| smearing `S` | Duan factor **per decile of μ**, from out-of-bag residuals (see *design notes*) |
| guard | beyond the per-land-cover training 99.5th percentile of MODIS count/FRP, the RF correction shrinks linearly to 0 at 2× the limit. The GLM then carries the extrapolation, and the row gets `extrapolated = true` |
| intervals | quantile regression forest on the same residuals → 5/50/95 %. For extrapolated rows the centre shrinks with the correction and the spread is widened |

There are **no year fixed effects and no time trend**, because they can't be estimated for 2002–2011. Orbit drift is a
flag, `year ≥ 2021` (`time.drift_cutoff_year`).

**Validation** runs in the backcast direction and uses year blocks only (never random splits):
fold A trains on 2017–2026 and tests on 2012–2016; fold B trains on 2012–2016 and tests on 2017–2026.
Leave-one-year-out is optional (`validation.leave_one_year_out`). Four models run on identical folds:
the naive global ratio, the GLM only, the **hybrid**, and a **stacked** alternative. The stacked model
combines GLM + RF + ExtraTrees through a non-negative linear meta-model trained only on year-grouped
out-of-fold predictions. Metrics: mean Poisson deviance, MAE, RMSE(log1p), 90 % interval coverage,
annual-total bias per region.

Outputs in `reports/`:
- `backcast_summary.md`: fold means, % improvement vs naive, and the recommendation
- `backcast_validation.{csv,md}`: per-fold metrics
- `backcast_annual_totals.png`: predicted vs actual annual totals per region, every year held out
- `backcast_improvement.png`: % improvement over the naive ratio
- `glm_conversion_factors.{csv,md}`: per-land-cover MODIS→VIIRS factors with 95 % CIs
- `glm_coefficients.csv`: every GLM term

After validation, the hybrid is **retrained on all of 2012–2026** and backcasts 2002–2011. That run writes
`artifacts/combined_2002_2026.parquet`:

`cell_id, lat, lon, region, land_cover, year, month, y, q05, q50, q95, p_any, extrapolated, source`

Here `source` is `backcast` (2002–2011) or `observed` (2012+). For observed rows, q05 = q50 = q95 = y.
`p_any` is P(any fire): 0/1 for observed rows, and the NB zero-mass probability for backcast rows.

## Model 2: forecast (`fire_model/forecast.py`)

- **Features:** lagged VIIRS-equivalent activity (1, 2, 3, 12 months), per-cell climatology for that calendar month
  (computed from training rows only), land cover, region, month, and optional climate covariates.
  To add climate covariates, set `paths.climate` and `forecast.climate_columns`; the table is joined on
  `cell_id, year, month`. With them unset, the model runs without them.
- **Models:** HistGradientBoosting with Poisson loss for the expected count, and logistic regression for P(fire).
- **P(fire) labels:** backcast rows use `p_any` as a soft label, because their `y` is an expected value.
- **Weights:** observed rows count 1. Backcast rows count `1/(1 + (q95−q05)/(q50+1))`, clipped to ≥ 0.1.
- **Validation:** rolling origin. For each T in 2022–2026, train on years < T and test on T. The baseline is
  seasonal climatology. Results: `reports/forecast_validation.{csv,md}`.

Artifacts are written with joblib. Each carries a metadata block: training years, features, guard limits,
category levels, package versions and seed.

## API

```sh
uvicorn api:app --reload        # FIRE_MODEL_CONFIG=/path/config.yaml to use another config
```

```sh
curl -s localhost:8000/health

# VIIRS-equivalent for one or more MODIS cell-months
curl -s localhost:8000/backcast/predict -H 'content-type: application/json' -d '{
  "rows": [{"modis_count": 12, "modis_frp_sum": 420, "aqua_share": 0.5, "high_conf_share": 0.3,
            "land_cover": "forest", "region": "US_West", "month": 8, "year": 2005}]}'

# full cell-month series for the calendar (paged with offset/limit)
curl -s localhost:8000/backcast/batch -H 'content-type: application/json' -d '{
  "region": "US_West", "year_from": 2002, "year_to": 2026, "limit": 1000}'

# next-month forecast (target month at most one month after the data end)
curl -s localhost:8000/forecast -H 'content-type: application/json' -d '{
  "cell_ids": ["38.25_-121.75"], "target_month": "2026-11"}'
```

Validation errors return 422 with a readable message, e.g. an unknown land cover lists the allowed values.
If artifacts are missing, the model endpoints return 503. CORS origins are set in `api.cors_origins`.

## Design notes (decisions made while building)

- **Any-detection term in the GLM.** With > 90 % zero cell-months, one slope on log1p(count) had to cover
  both the 0→1 jump and the scaling at large counts. It overshot about 8× on large fires.
  A per-land-cover `1{MODIS > 0}` term fixes this.
- **Binned smearing.** Log residuals are heteroscedastic. A single Duan factor over-predicted
  zero-MODIS cell-months by about 40 % and under-predicted mid-size ones by 10–20 %, which put annual
  totals off by about 10 %. One factor per decile of μ brings every size bin to within about ±12 %. The
  stacked comparator gets the same treatment so the comparison is fair.
- **Soft fire labels for backcast years.** Backcast `y` is an expected value (e.g. 0.3), so `y > 0` labelled
  almost every 2002–2011 cell-month a fire. That corrupted P(fire) training and the fire-frequency
  climatology. Backcast rows now carry `p_any` from the NB predictive distribution.
- **Fold B and the drift flag.** Fold B trains only on pre-2021 years, so the drift flag can't be estimated there.
  It is dropped automatically, and the validation table notes it.
- **Relationship to the team calendar (SRS v3).** The team pipeline (`pipeline/`, `api/`) harmonises in
  the opposite direction: MODIS-equivalent counts, using Aqua + NOAA-20 with a 2025-01 splice. This module
  is a separate track. The team still has to decide which series the calendar shows.
