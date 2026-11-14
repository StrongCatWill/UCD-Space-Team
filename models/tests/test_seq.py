"""Tests for models/seq (sequence-backcast data prep, windows, baselines). Tiny hand-checkable inputs only:
no archive files, no data/seq arrays. Expected numbers are worked out in the comments."""
import json
import math

import numpy as np
import pandas as pd
import pytest

from seq import baselines, build_weekly, common, dataset, windows
from seq.common import DYN, LOOKAHEAD, LOOKBACK, STATIC, WIN


# ---------------------------------------------------------------- common: week calendar

def test_week_calendar_anchored_on_monday_2000_01_03():
    assert common.week_start(0) == np.datetime64("2000-01-03")
    # 2000 is a leap year: 2000-01-03 + 52*7 = 2001-01-01 (Monday); its Thursday 2001-01-04 -> week year 2001
    assert common.week_start(52) == np.datetime64("2001-01-01")
    assert common.week_year(np.array([0, 51, 52])).tolist() == [2000, 2000, 2001]
    # week 0's Thursday is 2000-01-06 -> day-of-year index 5
    assert common.week_doy(np.array([0])).tolist() == [5]
    # week 156 starts 2002-12-30 (Mon); its Thursday 2003-01-02 -> week year 2003 (year of the Thursday)
    assert common.week_start(156) == np.datetime64("2002-12-30")
    assert common.week_year(np.array([155, 156, 157])).tolist() == [2002, 2003, 2003]


def test_weeks_in_selects_by_week_year():
    w = common.weeks_in((2001, 2001), 200)
    assert w[0] == 52 and common.week_year(w).min() == 2001 and common.week_year(w).max() == 2001
    assert common.week_year(np.array([w[-1] + 1]))[0] == 2002


def test_time_features_are_unit_circle():
    tf = common.time_features(np.arange(60))
    assert tf.shape == (60, 2) and tf.dtype == np.float32
    np.testing.assert_allclose((tf ** 2).sum(1), 1.0, rtol=1e-5)


# ---------------------------------------------------------------- common: metrics

def test_poisson_deviance_hand_calc():
    # y=0, mu=1: 2*(0 - (0-1)) = 2 ; y=2, mu=1: 2*(2 ln 2 - 1) ; mean = 2 ln 2
    assert common.poisson_deviance(np.array([0.0, 2.0]), np.array([1.0, 1.0])) == pytest.approx(2 * math.log(2))
    assert common.poisson_deviance(np.array([3.0, 5.0]), np.array([3.0, 5.0])) == pytest.approx(0.0)


def test_metrics_bias_and_region_month_totals():
    y = np.array([0.0, 2.0, 4.0, 0.0])
    mu = np.array([1.0, 1.0, 4.0, 0.0])
    m = common.metrics(y, mu)
    assert m["mae"] == pytest.approx(0.5)               # (1 + 1 + 0 + 0) / 4
    assert m["total_bias_pct"] == pytest.approx(0.0)    # 6 vs 6
    assert m["mae_active"] == pytest.approx(2 / 3)      # last row inactive (y=0, mu<=0.5)
    # rows 0-1: region 1, week 0 (Jan 2000); rows 2-3: region 2, week 0 -> totals (2 vs 2), (4 vs 4)
    m2 = common.metrics(y, mu, cells_region=np.array([1, 1, 2, 2]), weeks=np.array([0, 0, 0, 0]))
    assert m2["region_month_mae"] == pytest.approx(0.0)
    assert m2["region_month_r2_log1p"] == pytest.approx(1.0)


# ---------------------------------------------------------------- windows

def test_window_offsets():
    assert WIN == LOOKBACK + LOOKAHEAD == len(windows.OFFS)
    assert windows.OFFS[0] == -(LOOKBACK - 1) and windows.OFFS[-1] == LOOKAHEAD
    assert len(windows.MODIS_COLS) == sum(d.startswith("m_") for d in DYN)


def test_window_active_hand_case():
    R = np.zeros((2, 20), np.float32)
    R[0, 10] = 3
    act = windows.window_active(R, np.arange(20))
    # window of week w covers w-7 .. w+2, so the detection in week 10 is seen from weeks 8..17
    assert np.nonzero(act[0])[0].tolist() == list(range(8, 18))
    assert not act[1].any()


def test_valid_weeks_respects_window_edges_and_target_mask():
    W = 30                                   # weeks 0..29 all fall in week-year 2000
    M = np.ones(W, bool)
    M[10] = False                            # S-NPP outage week
    w = windows.valid_weeks((2000, 2000), M, W)
    assert w[0] == 7 and w[-1] == 27         # need w-7 >= 0 and w+2 < 30
    assert 10 not in w and len(w) == 20


def _one_fire_world(C=50, W=30):
    R = np.zeros((C, W), np.float32)
    R[0, 15] = 2
    return R, np.ones(W, bool)


def test_sample_rows_keeps_every_active_row_and_reweights_empty_ones():
    R, M = _one_fire_world()
    cells, wk, wt, info = windows.sample_rows(R, M, (2000, 2000), np.random.default_rng(0), empty_ratio=20.0)
    # valid weeks 7..27 (21); active rows: cell 0, weeks 13..22 -> 10 ; empty rows 50*21 - 10 = 1040
    assert info["n_active"] == 10 and info["n_empty_total"] == 1040
    s = info["empty_rate"]
    assert s == pytest.approx(20 * 10 / 1040)
    act = windows.window_active(R, wk) [cells, np.arange(len(cells))]
    assert act.sum() == 10 and np.all(wt[act] == 1.0)
    np.testing.assert_allclose(wt[~act], 1.0 / s, rtol=1e-6)
    # inverse-probability weights keep the empty-row total unbiased (sd ~ 6%; allow 25%)
    assert wt[~act].sum() == pytest.approx(1040, rel=0.25)
    assert len(cells) == len(wk) == len(wt) == info["n_rows"]


def test_sample_rows_keeps_everything_when_ratio_is_large():
    R, M = _one_fire_world()
    _, _, wt, info = windows.sample_rows(R, M, (2000, 2000), np.random.default_rng(1), empty_ratio=1e6)
    assert info["empty_rate"] == 1.0 and info["n_rows"] == 50 * 21 and np.all(wt == 1.0)


def test_gather_aligns_window_offsets_and_flat_feature_width():
    C, W = 3, 30
    X = np.zeros((C, W, len(DYN)), np.float32)
    X[:, :, 0] = np.arange(W)[None, :]               # feature 0 = week index
    S = np.ones((C, len(STATIC)), np.float32)
    cells, weeks = np.array([0, 2]), np.array([10, 20])
    seq, st = windows.gather(X, S, cells, weeks)
    assert seq.shape == (2, WIN, len(DYN)) and st.shape == (2, len(STATIC) + 2)
    assert seq[0, :, 0].tolist() == list(range(3, 13))      # 10-7 .. 10+2
    assert seq[1, LOOKBACK - 1, 0] == 20                     # target week sits at index LOOKBACK-1
    F = windows.flat_features(seq, st)
    assert F.shape == (2, len(windows.MODIS_COLS) * WIN + 2 + 2 + len(STATIC) + 2)


def test_flat_features_window_summary_is_total_count():
    seq = np.zeros((1, WIN, len(DYN)), np.float32)
    seq[0, [2, 5], DYN.index("m_n")] = np.log1p([3.0, 4.0])
    st = np.zeros((1, len(STATIC) + 2), np.float32)
    F = windows.flat_features(seq, st)
    base = len(windows.MODIS_COLS) * WIN + 2
    assert F[0, base] == pytest.approx(math.log1p(7))       # log1p(3 + 4)
    assert F[0, base + 1] == 2                               # two weeks with detections


def test_dense_rows_is_full_grid():
    cells, wk = windows.dense_rows(3, np.array([5, 6]))
    assert cells.tolist() == [0, 0, 1, 1, 2, 2] and wk.tolist() == [5, 6, 5, 6, 5, 6]


# ---------------------------------------------------------------- build_weekly

def _raw(rows):
    return pd.DataFrame(rows, columns=["latitude", "longitude", "acq_date", "acq_time", "satellite",
                                       "confidence", "frp", "daynight", "type"]).astype({"acq_time": str})


def test_prep_cells_weeks_and_local_solar_time():
    df = _raw([[-0.1, -121.3, "2000-01-10", "534", "Terra", "85", 10.0, "D", 0]])
    df["acq_date"] = pd.to_datetime(df["acq_date"])
    d = build_weekly.prep(df, "modis")
    assert d["lat_i"].iloc[0] == -1          # floor(-0.4)
    assert d["lon_i"].iloc[0] == -486        # floor(-485.2)
    assert d["week"].iloc[0] == 1            # 2000-01-10 is the second Monday
    # 534 -> 05:34 UTC = 334 min; + lon*4 = -485.2 min -> (334 - 485.2) mod 1440 = 1288.8 min = 21.48 h
    assert d["lst_h"].iloc[0] == pytest.approx(21.48, abs=1e-3)
    assert d["sat"].iloc[0] == "T" and d["conf"].iloc[0] == 85


def test_prep_unknown_modis_satellite_fails_loudly():
    df = _raw([[40.0, -120.0, "2000-01-10", "1200", "Aura", "50", 1.0, "D", 0]])
    df["acq_date"] = pd.to_datetime(df["acq_date"])
    with pytest.raises(ValueError, match="unknown MODIS satellite"):
        build_weekly.prep(df, "modis")


def test_prep_viirs_confidence_letters():
    df = _raw([[40.0, -120.0, "2015-07-01", "2048", "N", c, 1.0, "N", 0] for c in ("l", "n", "h")])
    df["acq_date"] = pd.to_datetime(df["acq_date"])
    d = build_weekly.prep(df, "viirs")
    assert d["conf"].tolist() == [0.0, 50.0, 100.0] and set(d["sat"]) == {"N"} and d["day"].sum() == 0


def test_read_one_keeps_vegetation_type_only(tmp_path):
    p = tmp_path / "modis_2003_Canada.csv"
    p.write_text("latitude,longitude,brightness,acq_date,acq_time,satellite,confidence,frp,daynight,type\n"
                 "50.1,-120.2,310,2003-07-01,534,Aqua,61,8.2,D,0\n"
                 "50.1,-120.2,330,2003-07-01,534,Aqua,90,80.0,D,2\n"
                 "19.4,-155.3,340,2003-07-02,2105,Terra,100,99.0,N,1\n")
    df = build_weekly.read_one(str(p))
    assert len(df) == 1 and df["frp"].iloc[0] == pytest.approx(8.2)
    assert df["acq_time"].iloc[0] == "534"          # read as a string, not an int


def test_agg_modis_hand_counts():
    df = _raw([[40.01, -120.01, "2000-01-10", "1830", "Terra", "85", 10.0, "D", 0],
               [40.02, -120.02, "2000-01-12", "0930", "Aqua", "20", 5.0, "N", 0]])
    df["acq_date"] = pd.to_datetime(df["acq_date"])
    a = build_weekly.agg_modis(build_weekly.prep(df, "modis")).iloc[0]
    assert (a.m_n, a.m_terra, a.m_aqua, a.m_night, a.m_hi, a.m_lo, a.m_days) == (2, 1, 1, 1, 1, 1, 2)
    assert a.m_frp == pytest.approx(15.0) and a.m_frp_max == pytest.approx(10.0)
    assert a.m_conf_sum == pytest.approx(105.0) and a.m_frp_terra == pytest.approx(10.0)


# ---------------------------------------------------------------- baselines

# 2 cells x 4 weeks (all in January 2000 -> calendar month 0), both cells in region 0
R4 = np.array([[2, 0, 4, 0], [1, 0, 0, 0]], np.float32)            # MODIS counts, sum 7
FM4 = np.array([[10, 0, 20, 0], [5, 0, 0, 0]], np.float32)         # MODIS FRP, sum 35
Y4 = np.stack([np.array([[4, 0, 6, 1], [3, 0, 0, 0]], np.float32),   # VIIRS counts, sum 14
               np.array([[30, 0, 40, 2], [5, 0, 0, 0]], np.float32)],  # VIIRS FRP, sum 77
              axis=-1)
W4 = np.arange(4)


def test_month_of_is_calendar_month_of_the_thursday():
    assert baselines.month_of(np.array([0, 4, 52])).tolist() == [0, 1, 0]   # Jan, Feb (Feb 3 2000), Jan 2001


def test_ratio_fit_hand_calc():
    fit = baselines.ratio_fit(R4, FM4, Y4, None, np.array([0, 0]), W4)
    np.testing.assert_allclose(fit["r_global"], [14 / 7, 77 / 35])          # 2.0, 2.2
    row = fit["table"].iloc[0]
    # MODIS=0 rows: 5 (cell0 w1,w3; cell1 w1,w2,w3); VIIRS on them: count 1, FRP 2
    # r  = (14 - 1 + 50*2.0) / (7 + 50)           = 113/57
    # rf = (77 - 2 + 500*2.2) / (35 + 500)        = 1175/535
    assert row["r"] == pytest.approx(113 / 57)
    assert row["rf"] == pytest.approx(1175 / 535)
    assert row["b"] == pytest.approx(1 / 5) and row["bf"] == pytest.approx(2 / 5)


def test_ratio_predict_global_and_region_month():
    fit = baselines.ratio_fit(R4, FM4, Y4, None, np.array([0, 0]), W4)
    g = baselines.ratio_predict(fit, R4, FM4, np.array([0, 0]), W4, "global")
    np.testing.assert_allclose(g[..., 0], 2.0 * R4)
    rm = baselines.ratio_predict(fit, R4, FM4, np.array([0, 0]), W4, "rm")
    assert rm[0, 0, 0] == pytest.approx(2 * 113 / 57, rel=1e-6)             # MODIS > 0 -> r * m
    assert rm[0, 1, 0] == pytest.approx(0.2)                                 # MODIS = 0 -> background b
    # a region never seen in training falls back to the global ratio and zero background
    un = baselines.ratio_predict(fit, R4, FM4, np.array([7, 7]), W4, "rm")
    np.testing.assert_allclose(un[..., 0], np.where(R4 > 0, 2.0 * R4, 0.0), rtol=1e-6)


def test_score_reports_both_targets():
    P = np.stack([2.0 * R4, 2.2 * FM4], -1)
    # two regions -> region_month r2_log1p is well defined (one group per cell would make its denominator 0)
    res = baselines.score(Y4, P, np.array([0, 1]), W4)
    assert set(res) == {"count", "frp"}
    assert res["count"]["total_bias_pct"] == pytest.approx(0.0)              # 2 * 7 = 14
    assert "region_month_mae" in res["frp"]
    assert math.isfinite(res["count"]["region_month_r2_log1p"])


# ---------------------------------------------------------------- dataset (end to end on a tiny parquet)

def test_dataset_builds_dense_arrays(tmp_path, monkeypatch):
    zero = dict(m_terra=0, m_aqua=0, m_night=0, m_hi=0, m_lo=0, m_frp=0.0, m_frp_max=0.0, m_conf_sum=0.0,
                m_days=0, v_n=0, v_frp=0.0)
    # cell A (lat_i 160, lon_i -486): MODIS in weeks 157/158 (2003), VIIRS too; cell B: VIIRS only in week 159
    rows = [
        {**zero, "lat_i": 160, "lon_i": -486, "week": 157, "m_n": 3, "m_aqua": 3, "m_frp": 30.0, "m_frp_max": 15.0,
         "m_conf_sum": 240.0, "m_days": 2, "m_night": 1, "v_n": 5, "v_frp": 20.0},
        {**zero, "lat_i": 160, "lon_i": -486, "week": 158, "m_n": 1, "m_terra": 1, "m_frp": 10.0, "m_frp_max": 10.0,
         "m_conf_sum": 50.0, "m_days": 1},
        {**zero, "lat_i": 200, "lon_i": -480, "week": 159, "m_n": 0, "v_n": 2, "v_frp": 3.0},
    ]
    pd.DataFrame(rows).to_parquet(tmp_path / "cell_week.parquet", index=False)
    week157 = pd.date_range("2003-01-06", periods=7)
    sd = pd.concat([pd.DataFrame({"date": week157, "sat": "N", "n": 1}),            # S-NPP all 7 days
                    pd.DataFrame({"date": week157[:3], "sat": "T", "n": 1}),        # Terra 3 of 7
                    pd.DataFrame({"date": week157, "sat": "A", "n": 1})])
    sd.to_parquet(tmp_path / "sensor_daily.parquet", index=False)
    monkeypatch.setattr(dataset, "DATA", str(tmp_path))

    dataset.main()

    meta = json.loads((tmp_path / "meta.json").read_text())
    assert (meta["C"], meta["W"], meta["cells_with_modis"]) == (2, 160, 1)
    X = np.load(tmp_path / "dyn.npy")
    Y = np.load(tmp_path / "target.npy")
    ok = np.load(tmp_path / "target_ok.npy")
    S = np.load(tmp_path / "static.npy")
    assert X.shape == (2, 160, len(DYN)) and Y.shape == (2, 160, 2) and S.shape == (2, len(STATIC))
    assert np.nonzero(ok)[0].tolist() == [157]                         # only the full S-NPP week
    assert X[0, 157, DYN.index("m_n")] == pytest.approx(math.log1p(3))
    assert X[0, 157, DYN.index("m_conf")] == pytest.approx(0.8)          # 240 / 3 / 100
    assert X[1, 157, DYN.index("cov_t")] == pytest.approx(3 / 7)         # coverage is NA-wide, every cell
    assert X[0, 159].sum() == 0 and Y[1, 159].tolist() == [2.0, 3.0]
    np.testing.assert_array_equal(np.load(tmp_path / "raw_mcount.npy")[0, 157:159], [3, 1])
    assert S[:, 5].tolist() == [1.0, 0.0]                                # has_modis
    cells = pd.read_parquet(tmp_path / "cells.parquet")
    # cell A centre (40.125, -121.375): region = (floor(8.03)+90)*1000 + (floor(-24.28)+180) = 98*1000 + 155
    assert cells.loc[0, "region"] == 98155
    assert (cells.loc[0, "lat"], cells.loc[0, "lon"]) == (40.125, -121.375)
