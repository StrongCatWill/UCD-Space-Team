"""Plugin contract for hotspot data sources.

A source is any class registered with @register("<kind>") that implements
    fetch(start: date, end: date) -> pandas.DataFrame   (UTC acquisition dates, inclusive)
and returns the *standard columns* below. Plug a new satellite/feed in by adding a class
and one entry in sources.json — nothing else in the service changes.
"""
from __future__ import annotations
import datetime as dt
from typing import Callable
import numpy as np
import pandas as pd

STD_COLS = ['lat', 'lon', 'day', 'hhmm', 'sat', 'conf', 'frp', 'night', 'type']
# sat codes used everywhere: T=Terra, A=Aqua (MODIS) · S=S-NPP, J1=NOAA-20, J2=NOAA-21 (VIIRS)
SAT_MAP = {'Terra': 'T', 'T': 'T', 'Aqua': 'A', 'A': 'A', 'SNPP': 'S', 'N': 'S',
           'N20': 'J1', 'NOAA20': 'J1', 'N21': 'J2', 'NOAA21': 'J2'}
CONF_MAP = {'l': 0, 'n': 50, 'h': 100, 'low': 0, 'nominal': 50, 'high': 100}

REGISTRY: dict[str, type] = {}


def register(kind: str) -> Callable[[type], type]:
    def deco(cls: type) -> type:
        REGISTRY[kind] = cls
        cls.kind = kind
        return cls
    return deco


def standardize(d: pd.DataFrame, sat_override: str | None = None) -> pd.DataFrame:
    """Any FIRMS CSV flavour (archive, NRT download, Area API) -> standard columns.
    Same dtypes as the training notebook (float32 lat/lon) so features match bit-for-bit."""
    if d.empty:
        return pd.DataFrame({c: pd.Series(dtype='float32') for c in STD_COLS})
    c = d['confidence']
    if pd.api.types.is_numeric_dtype(c):
        conf = c.astype('int16').values
    else:
        conf = c.astype(str).str.strip().str.lower().map(CONF_MAP).fillna(-1).astype('int16').values
    sat = (np.full(len(d), sat_override, dtype=object) if sat_override
           else d['satellite'].astype(str).map(SAT_MAP).values)
    return pd.DataFrame({
        'lat': d['latitude'].astype('float32').values,
        'lon': d['longitude'].astype('float32').values,
        'day': pd.to_datetime(d['acq_date']).values.astype('datetime64[D]').astype('int32'),
        'hhmm': pd.to_numeric(d['acq_time']).astype('int16').values,
        'sat': sat,
        'conf': conf,
        'frp': d['frp'].astype('float32').values,
        'night': (d['daynight'].astype(str) == 'N').values,
        'type': d['type'].astype('int8').values if 'type' in d else np.int8(-1),   # -1 = unknown (NRT)
    })


def clip_days(df: pd.DataFrame, start: dt.date, end: dt.date) -> pd.DataFrame:
    a = int(np.datetime64(start, 'D').astype('int32')); b = int(np.datetime64(end, 'D').astype('int32'))
    return df[(df['day'] >= a) & (df['day'] <= b)]
