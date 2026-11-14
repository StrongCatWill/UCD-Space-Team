"""One-off: create artifacts/ingest_state.npz (static-source mask + 26-year monthly climatology),
which /ingest needs to build features for new days exactly like training did.

  python build_ingest_state.py --data /content/data --art artifacts

--data is the folder with the unzipped FIRMS DL_FIRE_* folders (the same zip used for training).
New notebooks save this file in step 26 automatically; this script is for artifacts made before that.
"""
import argparse
import glob
import os
import sys
import numpy as np
import pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fireservice.sources.base import standardize      # noqa: E402
from fireservice.features import key                  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument('--data', required=True); ap.add_argument('--art', default='artifacts')
a = ap.parse_args()

# 1) static mask: archive rows (confidence-filtered) with type 1/2/3, expanded to the 3x3 0.01° neighbourhood
COLS = ['latitude', 'longitude', 'acq_date', 'acq_time', 'satellite', 'confidence', 'frp', 'daynight', 'type']
keys = []
for f in sorted(glob.glob(f'{a.data}/DL_FIRE_*/fire_archive_*.csv')):
    d = standardize(pd.read_csv(f, usecols=COLS, engine='pyarrow'))
    d = d[(d['conf'] >= 30) & (d['type'] != 0)]
    keys.append(np.unique(key(d['lat'].values, d['lon'].values)))
    print(os.path.basename(f), len(d), 'static rows')
sk = np.unique(np.concatenate(keys))
offs = np.array([di * 40000 + dj for di in (-1, 0, 1) for dj in (-1, 0, 1)])
mask_keys = np.unique((sk[:, None] + offs[None, :]).ravel())

# 2) hclim: log1p(mean monthly harmonized hotspots 2001-2021) per modelled cell (notebook step 17)
R = np.load(f'{a.art}/recent.npz')
cells = pd.DataFrame({'ci': R['ci'], 'cj': R['cj']}); cells['c'] = np.arange(len(cells))
cal = pd.read_parquet(f'{a.art}/calendar.parquet')
cm = cal[(cal['ym'] >= np.datetime64('2001-01')) & (cal['ym'] <= np.datetime64('2021-12'))].copy()
cm['m'] = pd.to_datetime(cm['ym']).dt.month
cm = cm.merge(cells, on=['ci', 'cj']).groupby(['c', 'm'])['H'].sum() / 21
hclim = np.zeros((len(cells), 13), 'float32')
hclim[cm.index.get_level_values(0), cm.index.get_level_values(1)] = np.log1p(cm.values)

np.savez_compressed(f'{a.art}/ingest_state.npz', mask_keys=mask_keys, hclim=hclim)
print('saved', f'{a.art}/ingest_state.npz', '| mask keys', len(mask_keys), '| cells', len(cells))
