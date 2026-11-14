import pandas as pd

from fire_model import artifacts
from fire_model.forecast import make_supervised, sample_weights


def test_forecast_outputs_valid(trained):
    model, meta = artifacts.load(trained / "forecast.joblib")
    series = pd.read_parquet(trained / "combined_2002_2026.parquet")
    sup = make_supervised(series, model.lags)
    p = model.predict(sup[sup["year"] == 2026])
    assert (p["expected"] >= 0).all()
    assert p["p_fire"].between(0, 1).all()


def test_backcast_rows_down_weighted(trained):
    series = pd.read_parquet(trained / "combined_2002_2026.parquet")
    w = sample_weights(series, 0.1)
    obs = series["source"] == "observed"
    assert (w[obs.to_numpy()] == 1).all()
    assert (w[~obs.to_numpy()] <= 1).all() and (w[~obs.to_numpy()] >= 0.1).all()
    assert w[~obs.to_numpy()].mean() < 1


def test_combined_series_sources(trained):
    s = pd.read_parquet(trained / "combined_2002_2026.parquet")
    assert set(s.loc[s["year"] <= 2011, "source"]) == {"backcast"}
    assert set(s.loc[s["year"] >= 2012, "source"]) == {"observed"}
    assert not s.duplicated(["cell_id", "year", "month"]).any()
    assert s["p_any"].between(0, 1).all()
    assert set(s.loc[s["source"] == "observed", "p_any"].unique()) <= {0.0, 1.0}
    # backcast rows must not all look like fires just because their expected value is > 0
    assert s.loc[s["source"] == "backcast", "p_any"].mean() < 0.9
