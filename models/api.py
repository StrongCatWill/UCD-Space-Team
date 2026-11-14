"""FastAPI service for the backcast (Model 1) and forecast (Model 2). Unit: grid cell x month.

    uvicorn api:app --reload            # config from $FIRE_MODEL_CONFIG or fire_model/config.yaml
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Literal

import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, field_validator, model_validator

from fire_model import artifacts
from fire_model.config import load_config
from fire_model.data import load_climate
from fire_model.forecast import query_rows

STATE: dict[str, Any] = {}


def _load_state() -> None:
    cfg = load_config()
    art = cfg.path("artifacts")
    STATE.clear()
    STATE["cfg"] = cfg
    for name, file in (("backcast", "backcast.joblib"), ("forecast", "forecast.joblib")):
        p = art / file
        if p.exists():
            STATE[name], STATE[f"{name}_meta"] = artifacts.load(p)
    series = art / "combined_2002_2026.parquet"
    if series.exists():
        s = pd.read_parquet(series)
        STATE["series"] = s
        STATE["cells"] = set(s["cell_id"].unique())
        ly = int(s["year"].max())
        STATE["last_month"] = (ly, int(s.loc[s["year"] == ly, "month"].max()))
    STATE["climate"] = load_climate(cfg)


@asynccontextmanager
async def lifespan(_: FastAPI):
    _load_state()   # models loaded once at startup
    yield
    STATE.clear()


app = FastAPI(title="Fire model API", version="0.1.0", lifespan=lifespan,
              description="VIIRS-equivalent backcast (MODIS-only years) and next-month fire-activity forecast "
                          "per grid cell-month. Not individual 375 m pixels.")
app.add_middleware(CORSMiddleware, allow_origins=load_config().raw["api"]["cors_origins"],
                   allow_methods=["*"], allow_headers=["*"])


def _require(name: str) -> Any:
    if name not in STATE:
        raise HTTPException(503, f"{name} is not loaded - run train_backcast.py / train_forecast.py first")
    return STATE[name]


# ------------------------------------------------------------------------------------------ models

class BackcastRow(BaseModel):
    modis_count: int = Field(ge=0, description="MODIS detections in the cell-month (Terra + Aqua)")
    modis_frp_sum: float = Field(ge=0, description="summed MODIS FRP, MW")
    modis_frp_max: float | None = Field(None, ge=0, description="max MODIS FRP, MW (default: mean per detection)")
    high_conf_share: float = Field(0.3, ge=0, le=1, description="share of high-confidence MODIS detections")
    aqua_share: float = Field(0.5, ge=0, le=1, description="share of MODIS detections from Aqua (satellite mix)")
    land_cover: str
    region: str
    month: int = Field(ge=1, le=12)
    year: int | None = Field(None, ge=2000, le=2030, description="sets the orbit-drift era flag; default pre-drift")

    @model_validator(mode="after")
    def _frp_consistent(self) -> "BackcastRow":
        if self.modis_count == 0 and self.modis_frp_sum > 0:
            raise ValueError("modis_frp_sum must be 0 when modis_count is 0")
        return self


class BackcastPredictRequest(BaseModel):
    rows: list[BackcastRow] = Field(min_length=1, max_length=10000)


class BackcastEstimate(BaseModel):
    viirs_equivalent: float
    q05: float
    q50: float
    q95: float
    extrapolated: bool


class BackcastPredictResponse(BaseModel):
    target: str
    unit: str
    estimates: list[BackcastEstimate]


class BatchRequest(BaseModel):
    region: str | list[str] | None = None
    year_from: int = Field(2002, ge=1990, le=2100)
    year_to: int = Field(2026, ge=1990, le=2100)
    cell_ids: list[str] | None = None
    source: Literal["backcast", "observed"] | None = None
    offset: int = Field(0, ge=0)
    limit: int = Field(50000, ge=1)

    @model_validator(mode="after")
    def _years(self) -> "BatchRequest":
        if self.year_from > self.year_to:
            raise ValueError(f"year_from ({self.year_from}) must be <= year_to ({self.year_to})")
        return self


class SeriesRow(BaseModel):
    cell_id: str
    lat: float
    lon: float
    region: str
    land_cover: str
    year: int
    month: int
    y: float
    q05: float
    q50: float
    q95: float
    extrapolated: bool
    source: Literal["backcast", "observed"]


class BatchResponse(BaseModel):
    target: str
    total: int
    offset: int
    rows: list[SeriesRow]


class ForecastRequest(BaseModel):
    cell_ids: list[str] = Field(min_length=1, max_length=5000)
    target_month: str = Field(pattern=r"^\d{4}-(0[1-9]|1[0-2])$", examples=["2026-11"])

    @field_validator("cell_ids")
    @classmethod
    def _unique(cls, v: list[str]) -> list[str]:
        return list(dict.fromkeys(v))


class CellForecast(BaseModel):
    cell_id: str
    target_month: str
    expected_activity: float
    p_fire: float
    climatology: float


class ForecastResponse(BaseModel):
    unit: str
    label: str
    forecasts: list[CellForecast]


# --------------------------------------------------------------------------------------- endpoints

@app.get("/health")
def health() -> dict[str, Any]:
    meta = {k: STATE.get(f"{k}_meta", {}) for k in ("backcast", "forecast")}
    return {
        "status": "ok",
        "backcast_loaded": "backcast" in STATE,
        "forecast_loaded": "forecast" in STATE,
        "series_rows": int(len(STATE["series"])) if "series" in STATE else 0,
        "last_month": "%04d-%02d" % STATE["last_month"] if "last_month" in STATE else None,
        "backcast_training_years": meta["backcast"].get("training_years"),
        "target": meta["backcast"].get("target"),
        "land_cover_levels": meta["backcast"].get("land_cover_levels"),
        "region_levels": meta["backcast"].get("region_levels"),
    }


@app.post("/backcast/predict", response_model=BackcastPredictResponse)
def backcast_predict(req: BackcastPredictRequest) -> BackcastPredictResponse:
    model = _require("backcast")
    meta = STATE["backcast_meta"]
    lcs, regs = set(meta["land_cover_levels"]), set(meta["region_levels"])
    errors = []
    for i, r in enumerate(req.rows):
        if r.land_cover not in lcs:
            errors.append(f"rows[{i}].land_cover {r.land_cover!r} unknown; allowed: {sorted(lcs)}")
        if r.region not in regs:
            errors.append(f"rows[{i}].region {r.region!r} unknown; allowed: {sorted(regs)}")
    if errors:
        raise HTTPException(422, errors[:20])
    cnt = np.array([r.modis_count for r in req.rows])
    aqua = np.rint(cnt * np.array([r.aqua_share for r in req.rows])).astype(int)
    pre_drift = meta["backcast_years"][0]
    df = pd.DataFrame({
        "modis_count": cnt, "modis_count_aqua": aqua, "modis_count_terra": cnt - aqua,
        "modis_count_high_conf": np.rint(cnt * np.array([r.high_conf_share for r in req.rows])).astype(int),
        "modis_frp_sum": [r.modis_frp_sum for r in req.rows],
        "modis_frp_max": [r.modis_frp_max if r.modis_frp_max is not None else r.modis_frp_sum / max(r.modis_count, 1)
                          for r in req.rows],
        "land_cover": [r.land_cover for r in req.rows], "region": [r.region for r in req.rows],
        "month": [r.month for r in req.rows], "year": [r.year or pre_drift for r in req.rows],
    })
    p = model.predict(df)
    return BackcastPredictResponse(
        target=meta["target"], unit=meta["unit"],
        estimates=[BackcastEstimate(viirs_equivalent=round(float(a), 2), q05=round(float(b), 2),
                                    q50=round(float(c), 2), q95=round(float(d), 2), extrapolated=bool(e))
                   for a, b, c, d, e in p[["pred", "q05", "q50", "q95", "extrapolated"]].itertuples(index=False)])


@app.post("/backcast/batch", response_model=BatchResponse)
def backcast_batch(req: BatchRequest) -> BatchResponse:
    s = _require("series")
    cfg = STATE["cfg"]
    mask = s["year"].between(req.year_from, req.year_to)
    if req.region is not None:
        regions = [req.region] if isinstance(req.region, str) else req.region
        unknown = sorted(set(regions) - set(s["region"].unique()))
        if unknown:
            raise HTTPException(422, f"unknown region(s) {unknown}; allowed: {sorted(s['region'].unique())}")
        mask &= s["region"].isin(regions)
    if req.cell_ids:
        mask &= s["cell_id"].isin(req.cell_ids)
    if req.source:
        mask &= s["source"] == req.source
    sel = s[mask]
    max_rows = int(cfg.raw["api"]["max_batch_rows"])
    if req.limit > max_rows:
        raise HTTPException(400, f"limit {req.limit} exceeds max_batch_rows {max_rows}; page with offset/limit")
    page = sel.iloc[req.offset: req.offset + req.limit]
    return BatchResponse(target=STATE.get("backcast_meta", {}).get("target", cfg.target), total=int(len(sel)),
                         offset=req.offset, rows=page.to_dict(orient="records"))


@app.post("/forecast", response_model=ForecastResponse)
def forecast(req: ForecastRequest) -> ForecastResponse:
    model = _require("forecast")
    s = _require("series")
    unknown = [c for c in req.cell_ids if c not in STATE["cells"]]
    if unknown:
        raise HTTPException(422, f"{len(unknown)} unknown cell_id(s), e.g. {unknown[:10]}")
    year, month = map(int, req.target_month.split("-"))
    ly, lm = STATE["last_month"]
    if year * 12 + month > ly * 12 + lm + 1:
        nxt = ly * 12 + lm  # zero-based index of the month after the last
        raise HTTPException(422, f"target_month {req.target_month} is beyond the forecast horizon: data end at "
                                 f"{ly:04d}-{lm:02d}, so the latest forecastable month is "
                                 f"{nxt // 12:04d}-{nxt % 12 + 1:02d}")
    q = query_rows(s, req.cell_ids, year, month, model.lags, STATE.get("climate"))
    lag_cols = [f"lag_{k}" for k in model.lags]
    missing = q[q[lag_cols].isna().any(axis=1)]
    if len(missing):
        raise HTTPException(422, f"not enough history for {req.target_month} (needs lags {model.lags} months) "
                                 f"for cells {missing['cell_id'].tolist()[:10]}")
    p = model.predict(q)
    return ForecastResponse(
        unit="VIIRS-equivalent detections per grid cell-month", label="Experimental model estimate",
        forecasts=[CellForecast(cell_id=c, target_month=req.target_month, expected_activity=round(float(e), 2),
                                p_fire=round(float(pf), 3), climatology=round(float(cl), 2))
                   for c, e, pf, cl in zip(q["cell_id"], p["expected"], p["p_fire"], p["clim"])])
