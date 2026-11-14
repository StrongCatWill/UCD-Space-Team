"""NASA FIRMS Area API plugin.

URL: https://firms.modaps.eosdis.nasa.gov/api/area/csv/[MAP_KEY]/[SOURCE]/[west,south,east,north]/[DAY_RANGE]/[DATE]
 - returns [DATE] .. [DATE + DAY_RANGE - 1], DAY_RANGE 1..5, limit 5000 transactions / 10 min (FIRMS Area API page)
 - get a MAP_KEY at https://firms.modaps.eosdis.nasa.gov/api/  and export it as FIRMS_MAP_KEY
"""
from __future__ import annotations
import datetime as dt
import io
import os
import urllib.request
import pandas as pd
from .base import register, standardize, clip_days

PRODUCT_SAT = {
    'VIIRS_NOAA20_NRT': 'J1', 'VIIRS_NOAA20_SP': 'J1',
    'VIIRS_NOAA21_NRT': 'J2', 'VIIRS_NOAA21_SP': 'J2',
    'VIIRS_SNPP_NRT': 'S', 'VIIRS_SNPP_SP': 'S',      # S-NPP production ended 2026-11-02
    'MODIS_NRT': None, 'MODIS_SP': None,                # sat comes from the 'satellite' column
}
BASE = 'https://firms.modaps.eosdis.nasa.gov/api/area/csv'


def _http_get(url: str, timeout: float = 60) -> str:
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read().decode('utf-8')


@register('firms_api')
class FirmsApiSource:
    def __init__(self, product: str, mapKeyEnv: str = 'FIRMS_MAP_KEY', bbox=(-180, 18, -50, 72),
                 chunkDays: int = 5, name: str | None = None, http=None, **_):
        if product not in PRODUCT_SAT:
            raise ValueError(f'unknown FIRMS product {product}; one of {sorted(PRODUCT_SAT)}')
        self.product, self.key_env, self.bbox = product, mapKeyEnv, bbox
        self.chunk = max(1, min(int(chunkDays), 5))
        self.name = name or f'firms:{product}'
        self.http = http or _http_get          # injectable for tests

    def _key(self) -> str:
        k = os.environ.get(self.key_env, '')
        if not k:
            raise RuntimeError(f'{self.name}: environment variable {self.key_env} is empty (FIRMS MAP_KEY)')
        return k

    def fetch(self, start: dt.date, end: dt.date) -> pd.DataFrame:
        key, frames, d = self._key(), [], start
        area = ','.join(str(v) for v in self.bbox)
        while d <= end:
            n = min(self.chunk, (end - d).days + 1)
            text = self.http(f'{BASE}/{key}/{self.product}/{area}/{n}/{d.isoformat()}')
            if not text.lstrip().startswith('latitude'):
                # FIRMS answers errors as plain text; never echo the URL (it contains the key)
                raise RuntimeError(f'{self.name}: FIRMS error for {d}..+{n-1}: {text.strip()[:200]}')
            raw = pd.read_csv(io.StringIO(text))
            if len(raw):
                frames.append(standardize(raw, PRODUCT_SAT[self.product]))
            d += dt.timedelta(days=n)
        if not frames:
            return standardize(pd.DataFrame())
        return clip_days(pd.concat(frames, ignore_index=True), start, end)
