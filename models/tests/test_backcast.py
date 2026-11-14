import numpy as np
import pytest

from fire_model import artifacts
from fire_model.backcast import build_models


@pytest.fixture(scope="module")
def fitted(cfg, data):
    train = data[data["year"].between(2012, 2026)].reset_index(drop=True)
    y = train[cfg.target_col].to_numpy(float)
    return {k: m.fit(train, y) for k, m in build_models(cfg, include_stacked=False).items()}, train


def test_predictions_non_negative_and_intervals_ordered(fitted, data):
    models, _ = fitted
    back = data[data["year"].between(2002, 2011)].reset_index(drop=True)
    for name, m in models.items():
        p = m.predict(back)
        assert (p["pred"] >= 0).all(), name
        assert (p["q05"] >= 0).all(), name
        assert ((p["q05"] <= p["q50"]) & (p["q50"] <= p["q95"])).all(), name


def test_no_year_terms_in_backcast(fitted):
    models, _ = fitted
    cols = models["glm"].design.columns + list(models["hybrid"].rf.feature_names_in_)
    assert not [c for c in cols if "year" in c.lower()]


def test_extrapolation_guard_triggers(fitted):
    models, train = fitted
    h = models["hybrid"]
    row = train[train["modis_count"] > 0].head(1).copy()
    normal = h.predict(row)
    assert not normal["extrapolated"].iloc[0]
    lc = row["land_cover"].iloc[0]
    big = row.copy()
    big["modis_count"] = int(h.limits[lc]["modis_count"] * 5)
    big["modis_count_terra"] = big["modis_count"] // 2
    big["modis_count_aqua"] = big["modis_count"] - big["modis_count_terra"]
    big["modis_frp_sum"] = h.limits[lc]["modis_frp_sum"] * 5
    p = h.predict(big)
    assert p["extrapolated"].iloc[0]
    excess, shrink = h.guard(big)
    assert shrink[0] == 0.0
    # with the correction fully shrunk, the GLM carries the prediction
    assert p["pred"].iloc[0] == pytest.approx(h.glm.mu(big)[0], rel=1e-9)


def test_unseen_land_cover_is_extrapolated(fitted):
    models, train = fitted
    row = train.head(1).copy()
    row["land_cover"] = "glacier"
    assert models["hybrid"].predict(row)["extrapolated"].iloc[0]


def test_conversion_factors_have_ci(fitted):
    cf = fitted[0]["glm"].conversion_factors()
    assert (cf["ratio_ci_low"] <= cf["ratio_at_10"]).all() and (cf["ratio_at_10"] <= cf["ratio_ci_high"]).all()


def test_saved_artifact_metadata(trained):
    model, meta = artifacts.load(trained / "backcast.joblib")
    for key in ("training_years", "rf_features", "guard", "package_versions", "unit"):
        assert key in meta
    assert meta["training_years"] == [2012, 2026]
    assert "375 m" in meta["unit"]
