"""Typed loader for config.yaml (with optional overrides for tests)."""
from __future__ import annotations

import copy
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

DEFAULT_CONFIG = Path(__file__).with_name("config.yaml")
CANONICAL_COLUMNS = (
    "cell_id", "lat", "lon", "region", "land_cover", "year", "month",
    "modis_count", "modis_count_terra", "modis_count_aqua", "modis_count_high_conf",
    "modis_frp_sum", "modis_frp_max", "viirs_count", "viirs_frp_sum",
)


def _deep_merge(base: dict, over: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in over.items():
        out[k] = _deep_merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


@dataclass(frozen=True)
class Config:
    raw: dict[str, Any]
    root: Path

    @property
    def seed(self) -> int:
        return int(self.raw["seed"])

    @property
    def columns(self) -> dict[str, str]:
        return dict(self.raw["columns"])

    @property
    def target(self) -> str:
        t = self.raw["target"]
        if t not in ("count", "frp"):
            raise ValueError(f"config target must be 'count' or 'frp', got {t!r}")
        return t

    @property
    def target_col(self) -> str:
        """Canonical column holding the VIIRS target."""
        return "viirs_count" if self.target == "count" else "viirs_frp_sum"

    @property
    def drift_cutoff(self) -> int:
        return int(self.raw["time"]["drift_cutoff_year"])

    @property
    def viirs_years(self) -> tuple[int, int]:
        a, b = self.raw["time"]["viirs_years"]
        return int(a), int(b)

    @property
    def backcast_years(self) -> tuple[int, int]:
        a, b = self.raw["time"]["backcast_years"]
        return int(a), int(b)

    def path(self, key: str) -> Path | None:
        p = self.raw["paths"].get(key)
        if p is None:
            return None
        p = Path(p)
        return p if p.is_absolute() else (self.root / p).resolve()

    def section(self, name: str) -> dict[str, Any]:
        return copy.deepcopy(self.raw[name])


def load_config(path: str | Path | None = None, overrides: dict | None = None) -> Config:
    """Load config.yaml (or $FIRE_MODEL_CONFIG), deep-merging `overrides`."""
    path = Path(path or os.environ.get("FIRE_MODEL_CONFIG") or DEFAULT_CONFIG)
    raw = yaml.safe_load(path.read_text())
    if overrides:
        raw = _deep_merge(raw, overrides)
    missing = [c for c in CANONICAL_COLUMNS if c not in raw.get("columns", {})]
    if missing:
        raise ValueError(f"{path}: 'columns' is missing mappings for {missing}")
    root = Path(raw.get("root", "."))
    root = root if root.is_absolute() else (path.parent / root).resolve()
    return Config(raw=raw, root=root)
