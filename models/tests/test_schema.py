import numpy as np
import pytest

from fire_model.config import load_config
from fire_model.data import SchemaError, validate


def test_valid_table_passes(cfg, raw_table):
    out = validate(raw_table, cfg)
    assert len(out) == len(raw_table)
    assert "viirs_count" in out.columns  # canonical name, whatever the source column is called


def test_missing_columns_are_named(cfg, raw_table):
    src = cfg.columns["modis_frp_max"]
    with pytest.raises(SchemaError) as e:
        validate(raw_table.drop(columns=[src, cfg.columns["region"]]), cfg)
    msg = str(e.value)
    assert src in msg and "columns.modis_frp_max" in msg and "columns.region" in msg


def test_column_names_come_from_config(cfg_path, raw_table):
    cfg2 = load_config(cfg_path, overrides={"columns": {"region": "eco_zone"}})
    renamed = raw_table.rename(columns={"region": "eco_zone"})
    assert validate(renamed, cfg2)["region"].notna().all()


def test_viirs_value_before_coverage_rejected(cfg, raw_table):
    t = raw_table.copy()
    vc = cfg.columns["viirs_count"]
    t.loc[t["year"] == 2005, vc] = 3.0
    with pytest.raises(SchemaError, match="outside VIIRS coverage"):
        validate(t, cfg)


def test_null_inside_coverage_rejected(cfg, raw_table):
    t = raw_table.copy()
    t.loc[t.index[t["year"] == 2015][:5], cfg.columns["viirs_count"]] = np.nan
    with pytest.raises(SchemaError, match="nulls inside VIIRS coverage"):
        validate(t, cfg)


def test_duplicates_and_negatives_rejected(cfg, raw_table):
    with pytest.raises(SchemaError, match="duplicate"):
        validate(raw_table.iloc[[0, 0, 1]], cfg)
    t = raw_table.copy()
    t.loc[t.index[0], cfg.columns["modis_frp_sum"]] = -1
    with pytest.raises(SchemaError, match="negative"):
        validate(t, cfg)
