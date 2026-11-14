"""End-to-end checks against the real FIRMS files and the notebook's artifacts.

  python tests/test_service.py --data /content/data --art artifacts

1. ingest_state.npz rebuilds the notebook's hclim feature exactly
2. "rewind" the window by 7 days, re-ingest those days from the raw CSVs through the plugin path,
   and require the rebuilt features == the notebook's training features (parity)
3. FIRMS Area-API plugin parses API-format CSV identically to the local CSV plugin (HTTP is mocked)
4. backtest / accuracy metrics run and are sane
"""
import argparse
import datetime as dt
import glob
import io
import os
import shutil
import subprocess
import sys
import tempfile
import numpy as np
import pandas as pd
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
from fireservice.state import LiveState                                  # noqa: E402
from fireservice.sources import REGISTRY                                  # noqa: E402
from fireservice.sources.firms_api import FirmsApiSource                  # noqa: E402
from fireservice.sources.local_csv import LocalCsvSource                  # noqa: E402

ap = argparse.ArgumentParser(); ap.add_argument('--data', required=True); ap.add_argument('--art', required=True)
a = ap.parse_args()
tmp = tempfile.mkdtemp(prefix='fire_art_')
for f in ('config.json', 'recent.npz', 'model.pt', 'calendar.parquet'):
    shutil.copy(f'{a.art}/{f}', tmp)
ok = lambda c, m: print(('PASS ' if c else 'FAIL ') + m) or c
results = []

# 1 ------------------------------------------------------------------
subprocess.run([sys.executable, f'{ROOT}/build_ingest_state.py', '--data', a.data, '--art', tmp], check=True)
S = LiveState(tmp)
hcl_nb = S.X[:, -1, 9] * S.SD[9] + S.MU[9]
m_last = int(str(S.last)[5:7])
results.append(ok(np.allclose(S.HCLIM[:, m_last], hcl_nb, atol=1e-4), 'hclim matches notebook feature'))

# 2 ------------------------------------------------------------------
X0, Y0, last0 = S.X.copy(), S.Y.copy(), S.last
K = 7
np.savez_compressed(f'{tmp}/state_live.npz', X=X0[:, :-K], Y=Y0[:, :-K], clim=S.CLIM, static=S.ST,
                    ci=S.CI, cj=S.CJ, first_day=str(S.first))
S2 = LiveState(tmp)
assert S2.last == last0 - dt.timedelta(days=K)
lo, hi = S2.last + dt.timedelta(days=1), last0
src = LocalCsvSource(glob=f'{a.data}/DL_FIRE_*/fire_*.csv')
det = src.fetch(lo - dt.timedelta(days=1), hi + dt.timedelta(days=1))
rep = S2.ingest(det, lo, hi)
print('ingest report:', {k: rep[k] for k in ('updated', 'from_', 'to', 'viirsSats', 'rows', 'fireCellDays', 'warnings')})
diff = np.abs(S2.X[:, -K:] - X0[:, -K:]).max(axis=(0, 1))
print('max |diff| per feature:', np.round(diff, 6))
results.append(ok(S2.last == last0, f'window extended back to {last0}'))
results.append(ok(np.allclose(S2.X[:, -K:], X0[:, -K:], atol=2e-4), 'rebuilt features == training features'))
results.append(ok(np.allclose(S2.Y[:, -K:], Y0[:, -K:]), 'rebuilt labels == training labels'))
d, p_new, _, _ = S2.predict(np.arange(len(S2.CI)), last0)
d, p_old, _, _ = S.predict(np.arange(len(S.CI)), last0)
results.append(ok(np.allclose(p_new, p_old, atol=1e-4), 'forecasts identical after re-ingest'))

# 3 ------------------------------------------------------------------
nrt = glob.glob(f'{a.data}/DL_FIRE_J1V-C2_*/fire_nrt_*.csv')[0]
raw = pd.read_csv(nrt)
raw = raw[raw['acq_date'].between('2026-11-03', '2026-11-07')]
api_csv = raw.rename(columns={'brightness': 'bright_ti4', 'bright_t31': 'bright_ti5'}).to_csv(index=False)
calls = []
def fake_http(url):
    calls.append(url.split('/')[-3:])
    d0 = dt.date.fromisoformat(url.split('/')[-1]); n = int(url.split('/')[-2])
    r = raw[(pd.to_datetime(raw['acq_date']).dt.date >= d0) & (pd.to_datetime(raw['acq_date']).dt.date < d0 + dt.timedelta(days=n))]
    return r.rename(columns={'brightness': 'bright_ti4', 'bright_t31': 'bright_ti5'}).to_csv(index=False)
os.environ['FIRMS_MAP_KEY'] = 'TEST'
fs = FirmsApiSource(product='VIIRS_NOAA20_NRT', http=fake_http, chunkDays=5)
api = fs.fetch(dt.date(2026, 11, 3), dt.date(2026, 11, 9)).reset_index(drop=True)
loc = LocalCsvSource(glob=nrt).fetch(dt.date(2026, 11, 3), dt.date(2026, 11, 9)).reset_index(drop=True)
print('API calls (range/date):', calls)
results.append(ok(len(api) == len(loc) and api.equals(loc), f'FIRMS API plugin == local CSV plugin ({len(api)} rows)'))
results.append(ok(set(REGISTRY) >= {'firms_api', 'local_csv'}, f'registered plugins: {sorted(REGISTRY)}'))
bad = FirmsApiSource(product='VIIRS_NOAA20_NRT', http=lambda u: 'Invalid MAP_KEY.')
try:
    bad.fetch(dt.date(2026, 11, 3), dt.date(2026, 11, 3)); results.append(ok(False, 'error text raises'))
except RuntimeError as e:
    results.append(ok('TEST' not in str(e), f'FIRMS error surfaced without leaking key: {e}'))

# 4 ------------------------------------------------------------------
bt = S2.backtest(np.arange(len(S2.CI)), last0 - dt.timedelta(days=37), last0 - dt.timedelta(days=1))
for L in bt['leads'][:7]:
    print(f"lead {L['lead']}: n={L['n']:>6} BSS={L['brierSkillScore']:.3f} prAuc={L['prAuc']:.3f} "
          f"(clim {L['prAucClimatology']:.3f}) acc@0.5={L['at0_5']['accuracy']:.3f} f1={L['at0_5']['f1']:.3f}")
results.append(ok(len(bt['leads']) == 7 and all(np.isfinite(L['brier']) for L in bt['leads']), 'backtest produced 7 lead times'))
from sklearn.metrics import average_precision_score
from fireservice.metrics import average_precision
rs = np.random.default_rng(0); sc = rs.random(5000).round(2); ev = rs.random(5000) < sc * .3
results.append(ok(abs(average_precision(sc, ev) - average_precision_score(ev, sc)) < 1e-9, 'average_precision == sklearn'))
shutil.rmtree(tmp)
print(f'\n{sum(results)}/{len(results)} checks passed')
sys.exit(0 if all(results) else 1)
