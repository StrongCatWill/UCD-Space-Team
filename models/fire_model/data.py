"""Load the harmonised cell-month table and validate it against the expected schema.

Column names come from config.yaml (`columns:`); after loading, every frame uses the canonical names.
Unit of analysis: one grid cell per month (not individual 375 m pixels).
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from .config import CANONICAL_COLUMNS, Config

KEY = ["cell_id", "year", "month"]
INT_COLS = ["year", "month", "modis_count", "modis_count_terra", "modis_count_aqua", "modis_count_high_conf"]
FLOAT_COLS = ["lat", "lon", "modis_frp_sum", "modis_frp_max"]
VIIRS_COLS = ["viirs_count", "viirs_frp_sum"]
CAT_COLS = ["cell_id", "region", "land_cover"]


class SchemaError(ValueError):
    """The harmonised table does not match the expected schema."""


def read_table(path: Path) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(f"harmonised table not found: {path} (run make_synthetic.py, or set paths.data)")
    if path.suffix == ".parquet":
        return pd.read_parquet(path)
    if path.suffix == ".csv":
        return pd.read_csv(path)
    raise SchemaError(f"unsupported file type {path.suffix!r}; use .parquet or .csv")


def validate(df: pd.DataFrame, cfg: Config, source: str = "table") -> pd.DataFrame:
    """Rename configured columns to canonical names, coerce types and check invariants.

    Fails with a SchemaError naming every problem found (missing columns first).
    """
    mapping = cfg.columns
    missing = [f"{mapping[c]!r} (config columns.{c})" for c in CANONICAL_COLUMNS if mapping[c] not in df.columns]
    if missing:
        raise SchemaError(f"{source}: missing required columns: {', '.join(missing)}. "
                          f"Present columns: {sorted(map(str, df.columns))}")
    out = df[[mapping[c] for c in CANONICAL_COLUMNS]].copy()
    out.columns = list(CANONICAL_COLUMNS)

    problems: list[str] = []
    for c in INT_COLS + FLOAT_COLS + VIIRS_COLS:
        coerced = pd.to_numeric(out[c], errors="coerce")
        bad = coerced.isna() & out[c].notna()
        if bad.any():
            problems.append(f"{c}: {int(bad.sum())} non-numeric values (e.g. {out.loc[bad, c].iloc[0]!r})")
        out[c] = coerced
    for c in INT_COLS + FLOAT_COLS + CAT_COLS:
        if out[c].isna().any():
            problems.append(f"{c}: {int(out[c].isna().sum())} missing values (only VIIRS columns may be null)")
    if problems:
        raise SchemaError(f"{source}: " + "; ".join(problems))

    for c in INT_COLS:
        out[c] = out[c].astype(np.int64)
    for c in CAT_COLS:
        out[c] = out[c].astype(str)

    if not out["month"].between(1, 12).all():
        problems.append(f"month: values outside 1-12: {sorted(out.loc[~out['month'].between(1, 12), 'month'].unique())[:5]}")
    for c in INT_COLS[2:] + ["modis_frp_sum", "modis_frp_max"] + VIIRS_COLS:
        if (out[c] < 0).any():
            problems.append(f"{c}: {int((out[c] < 0).sum())} negative values")
    parts = out["modis_count_terra"] + out["modis_count_aqua"]
    if (parts != out["modis_count"]).any():
        problems.append(f"modis_count != terra + aqua in {int((parts != out['modis_count']).sum())} rows")
    if (out["modis_count_high_conf"] > out["modis_count"]).any():
        problems.append("modis_count_high_conf > modis_count in some rows")
    dup = out.duplicated(KEY)
    if dup.any():
        problems.append(f"{int(dup.sum())} duplicate (cell_id, year, month) rows, e.g. {out.loc[dup, KEY].iloc[0].tolist()}")

    v0, v1 = cfg.viirs_years
    in_cov = out["year"].between(v0, v1)
    for c in VIIRS_COLS:
        early = (~in_cov) & out[c].notna()
        if early.any():
            problems.append(f"{c}: {int(early.sum())} values outside VIIRS coverage {v0}-{v1} "
                            f"(must be null = no coverage, not a number)")
        gaps = in_cov & out[c].isna()
        if gaps.any():
            problems.append(f"{c}: {int(gaps.sum())} nulls inside VIIRS coverage {v0}-{v1} "
                            f"(zero detections must be 0, not null)")
    if problems:
        raise SchemaError(f"{source}: " + "; ".join(problems))
    return out.sort_values(KEY, ignore_index=True)


def filter_active_cells(df: pd.DataFrame, cfg: Config) -> pd.DataFrame:
    """Keep only cells with at least one MODIS or VIIRS detection ever (config grid.keep_cells_with_any_detection)."""
    if not cfg.raw["grid"].get("keep_cells_with_any_detection", True):
        return df
    activity = df.groupby("cell_id")[["modis_count", "viirs_count"]].sum(min_count=1).fillna(0).sum(axis=1)
    keep = activity[activity > 0].index
    return df[df["cell_id"].isin(keep)].reset_index(drop=True)


def load_harmonised(cfg: Config, path: Path | None = None) -> pd.DataFrame:
    """Read + validate + drop never-active cells. Returns canonical columns."""
    path = path or cfg.path("data")
    df = validate(read_table(path), cfg, source=str(path))
    return filter_active_cells(df, cfg)


def load_climate(cfg: Config) -> pd.DataFrame | None:
    """Optional climate covariates keyed by cell_id/year/month; None when paths.climate is null."""
    path = cfg.path("climate")
    if path is None:
        return None
    clim = read_table(path)
    m = cfg.columns
    keys = {m["cell_id"]: "cell_id", m["year"]: "year", m["month"]: "month"}
    cols = cfg.raw["forecast"].get("climate_columns", [])
    missing = [c for c in list(keys) + cols if c not in clim.columns]
    if missing:
        raise SchemaError(f"{path}: climate table missing columns {missing}")
    clim = clim[list(keys) + cols].rename(columns=keys)
    clim["cell_id"] = clim["cell_id"].astype(str)
    return clim
