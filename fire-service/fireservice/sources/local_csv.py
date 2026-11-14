"""Local folder plugin: any FIRMS CSV you downloaded by hand (archive, NRT or Area-API format).
Useful offline, for back-filling, and for tests."""
from __future__ import annotations
import datetime as dt
import glob
import pandas as pd
from .base import register, standardize, clip_days

COLS = ['latitude', 'longitude', 'acq_date', 'acq_time', 'satellite', 'confidence', 'frp', 'daynight']


@register('local_csv')
class LocalCsvSource:
    def __init__(self, glob: str, name: str | None = None, **_):
        self.pattern = glob
        self.name = name or f'local:{glob}'

    def fetch(self, start: dt.date, end: dt.date) -> pd.DataFrame:
        frames = []
        for f in sorted(glob.glob(self.pattern, recursive=True)):
            head = pd.read_csv(f, nrows=0).columns
            use = COLS + (['type'] if 'type' in head else [])
            d = pd.read_csv(f, usecols=use, engine='pyarrow')
            d = d[(pd.to_datetime(d['acq_date']) >= pd.Timestamp(start)) & (pd.to_datetime(d['acq_date']) <= pd.Timestamp(end))]
            if len(d):
                frames.append(standardize(d))
        if not frames:
            return standardize(pd.DataFrame())
        return clip_days(pd.concat(frames, ignore_index=True), start, end)
