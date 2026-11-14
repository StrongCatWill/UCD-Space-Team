"""Small synthetic world shared by all tests (fast settings, isolated tmp paths)."""
from __future__ import annotations

import sys

import pytest
import yaml

from fire_model.config import DEFAULT_CONFIG, load_config
from fire_model.data import load_harmonised
from make_synthetic import make


@pytest.fixture(scope="session")
def cfg_path(tmp_path_factory):
    d = tmp_path_factory.mktemp("fire_model")
    raw = yaml.safe_load(DEFAULT_CONFIG.read_text())
    raw["root"] = str(d)
    # synthetic world, independent of the real-data settings in config.yaml (data path, column map, years)
    raw["paths"]["data"] = "data/harmonised.parquet"
    raw["columns"]["region"] = "region"
    raw["time"]["viirs_years"] = [2012, 2026]
    raw["validation"]["folds"] = {"A": {"train": [2017, 2026], "test": [2012, 2016]},
                                  "B": {"train": [2012, 2016], "test": [2017, 2026]}}
    raw["synthetic"]["n_cells"] = 60
    small = {"n_estimators": 30, "min_samples_leaf": 20, "max_features": 0.5, "n_jobs": 1}
    raw["backcast"]["rf"] = dict(small)
    raw["backcast"]["qrf"] = dict(small)
    raw["backcast"]["stacked"] = {"enabled": False, "inner_folds": 3}
    raw["forecast"]["hgb"]["max_iter"] = 50
    raw["forecast"]["rolling_test_years"] = [2025, 2026]
    p = d / "config.yaml"
    p.write_text(yaml.safe_dump(raw))
    return p


@pytest.fixture(scope="session")
def cfg(cfg_path):
    return load_config(cfg_path)


@pytest.fixture(scope="session")
def raw_table(cfg):
    return make(cfg)


@pytest.fixture(scope="session")
def data(cfg, raw_table):
    path = cfg.path("data")
    path.parent.mkdir(parents=True, exist_ok=True)
    raw_table.to_parquet(path, index=False)
    return load_harmonised(cfg)


@pytest.fixture(scope="session")
def trained(cfg, cfg_path, data):
    """Run the real training entry points (final fits only) into the tmp artifacts dir."""
    import train_backcast
    import train_forecast
    train_backcast.fit_final(data, cfg)
    argv = sys.argv
    sys.argv = ["train_forecast.py", "--config", str(cfg_path)]
    try:
        train_forecast.main()
    finally:
        sys.argv = argv
    return cfg.path("artifacts")
