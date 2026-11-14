import pytest

from fire_model.config import load_config
from fire_model.forecast import build_forecast_model, make_supervised
from fire_model.validate import Fold, backcast_folds, check_no_leakage, split


def test_folds_are_year_disjoint_and_backcast_direction(cfg, data):
    folds = {f.name: f for f in backcast_folds(cfg)}
    assert min(folds["A"].train_set()) > max(folds["A"].test_set())   # train later, test earlier
    for f in folds.values():
        train, test = split(data, f, cfg.target_col)
        assert set(train["year"]).isdisjoint(set(test["year"]))
        assert set(test["year"]) <= f.test_set() and set(train["year"]) <= f.train_set()


def test_overlapping_fold_is_rejected():
    with pytest.raises(ValueError, match="both train and test"):
        check_no_leakage([Fold("bad", frozenset({2015, 2016}), frozenset({2016}))])


def test_overlapping_config_fold_raises(cfg_path):
    bad = load_config(cfg_path, overrides={"validation": {"folds": {"A": {"train": [2012, 2020], "test": [2018, 2026]}}}})
    with pytest.raises(ValueError):
        backcast_folds(bad)


def test_leave_one_year_out(cfg_path):
    c = load_config(cfg_path, overrides={"validation": {"leave_one_year_out": True}})
    loyo = [f for f in backcast_folds(c) if f.name.startswith("LOYO")]
    assert len(loyo) == 15 and all(len(f.test_set()) == 1 and not (f.train & f.test) for f in loyo)


def test_forecast_climatology_uses_training_years_only(cfg, trained):
    import pandas as pd
    series = pd.read_parquet(trained / "combined_2002_2026.parquet")
    sup = make_supervised(series, [1, 2, 3, 12])
    train = sup[sup["year"] < 2025]
    m1 = build_forecast_model(cfg).fit(train)
    poisoned = sup.copy()
    poisoned.loc[poisoned["year"] >= 2025, "y"] = 1e6       # test-year values must not matter
    m2 = build_forecast_model(cfg).fit(poisoned[poisoned["year"] < 2025])
    pd.testing.assert_frame_equal(m1.clim_cell, m2.clim_cell)
    assert m1.clim_cell["clim"].max() < 1e6
