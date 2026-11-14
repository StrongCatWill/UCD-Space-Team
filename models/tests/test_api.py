import importlib

import pytest
from fastapi.testclient import TestClient


@pytest.fixture(scope="module")
def client(cfg_path, trained, monkeypatch_module):
    monkeypatch_module.setenv("FIRE_MODEL_CONFIG", str(cfg_path))
    import api
    importlib.reload(api)
    with TestClient(api.app) as c:
        yield c


@pytest.fixture(scope="module")
def monkeypatch_module():
    mp = pytest.MonkeyPatch()
    yield mp
    mp.undo()


ROW = {"modis_count": 12, "modis_frp_sum": 420.0, "aqua_share": 0.5, "land_cover": "forest",
       "region": "US_West", "month": 8, "year": 2005}


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["backcast_loaded"] and r.json()["forecast_loaded"]


def test_backcast_predict(client):
    r = client.post("/backcast/predict", json={"rows": [ROW, {**ROW, "modis_count": 0, "modis_frp_sum": 0}]})
    assert r.status_code == 200, r.text
    for e in r.json()["estimates"]:
        assert e["viirs_equivalent"] >= 0 and e["q05"] <= e["q50"] <= e["q95"]


def test_backcast_predict_validation_errors(client):
    r = client.post("/backcast/predict", json={"rows": [{**ROW, "land_cover": "lava"}]})
    assert r.status_code == 422 and "allowed" in r.text
    r = client.post("/backcast/predict", json={"rows": [{**ROW, "month": 13}]})
    assert r.status_code == 422


def test_backcast_batch(client):
    r = client.post("/backcast/batch", json={"region": "US_West", "year_from": 2003, "year_to": 2004})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["total"] > 0
    assert {row["year"] for row in body["rows"]} <= {2003, 2004}
    assert {row["source"] for row in body["rows"]} == {"backcast"}
    assert client.post("/backcast/batch", json={"year_from": 2010, "year_to": 2005}).status_code == 422


def test_forecast(client):
    cells = client.post("/backcast/batch", json={"year_from": 2026, "year_to": 2026, "limit": 3}).json()["rows"]
    ids = [c["cell_id"] for c in cells]
    r = client.post("/forecast", json={"cell_ids": ids, "target_month": "2026-11"})
    assert r.status_code == 200, r.text
    for f in r.json()["forecasts"]:
        assert f["expected_activity"] >= 0 and 0 <= f["p_fire"] <= 1
    assert client.post("/forecast", json={"cell_ids": ids, "target_month": "2027-06"}).status_code == 422
    assert client.post("/forecast", json={"cell_ids": ids, "target_month": "2026-13"}).status_code == 422
