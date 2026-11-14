"""Raw hotspots -> model features. Line-for-line the same math as the training notebook
(steps 4-7, 10-11, 16-17), so features built here for new days match training features.
tests/test_service.py checks this against the notebook's recent.npz."""
from __future__ import annotations
import numpy as np
import pandas as pd

VIIRS = ('S', 'J1', 'J2')


def d2i(d) -> int: return int(np.datetime64(d, 'D').astype('int32'))
def i2d(i): return np.asarray(i).astype('datetime64[D]')
def month_of(days): return i2d(days).astype('datetime64[M]').astype(int) % 12 + 1
def doy_of(days): return (i2d(days) - i2d(days).astype('datetime64[Y]')).astype(int)   # 0..365


def key(lat, lon):
    return np.round(lat * 100).astype(np.int64) * 40000 + np.round(lon * 100).astype(np.int64) + 20000


def clean(det: pd.DataFrame, mask_keys: np.ndarray, grid: float) -> pd.DataFrame:
    """confidence >= 30 · drop non-vegetation heat sources · local solar date · grid cell."""
    det = det[det['conf'] >= 30]
    t = det['type'].values.astype(np.int16)
    known = t >= 0
    static = known & (t != 0)                                                   # archive/SP: type tells us
    masked = (~known) & np.isin(key(det['lat'].values, det['lon'].values), mask_keys)   # NRT: archive mask
    det = det[~(static | masked)].copy()
    hh = det['hhmm'].values
    lst = hh // 100 + (hh % 100) / 60 + det['lon'].values / 15
    det['dloc'] = (det['day'].values + np.floor(lst / 24)).astype('int32')
    det['ci'] = np.floor(det['lat'].values / grid).astype('int16')
    det['cj'] = np.floor(det['lon'].values / grid).astype('int16')
    return det


def build_days(det: pd.DataFrame, day_lo: int, day_hi: int, ctx: dict):
    """Unscaled features for local days [day_lo, day_hi].
    ctx: cells_ci, cells_cj, clim (Nc,366), hclim (Nc,13), k (sat -> 13-array, index = month), n_vs, features
    Returns Xu (Nc,nd,F) float32, Y (Nc,nd) float32, den (nd,) int, report dict."""
    days = np.arange(day_lo, day_hi + 1)
    nd, nc, F = len(days), len(ctx['cells_ci']), len(ctx['features'])
    v = det[det['sat'].isin(VIIRS) & (det['dloc'] >= day_lo) & (det['dloc'] <= day_hi)]
    if len(v):
        avail = (v.groupby(['dloc', 'sat']).size().unstack('sat', fill_value=0) > 0).reindex(days, fill_value=False)
    else:
        avail = pd.DataFrame(index=days)
    den = avail.sum(1).astype(int)
    report = {'viirsSats': sorted(avail.columns.tolist()), 'rows': int(len(v)), 'warnings': []}

    agg = (v.groupby(['sat', 'dloc', 'ci', 'cj']).agg(n=('frp', 'size'), frp=('frp', 'sum'), nn=('night', 'sum'))
             .reset_index())
    kv = np.ones((len(agg),), 'float32')
    mon = month_of(agg['dloc'].values)
    for s in agg['sat'].unique():
        if s not in ctx['k']:
            report['warnings'].append(f'no calibration factor for {s}; using k=1.0 (calibrate against NOAA-20)')
            continue
        sel = (agg['sat'] == s).values
        kv[sel] = ctx['k'][s][mon[sel]]
    agg['kn'] = agg['n'] * kv
    cd = (agg.groupby(['dloc', 'ci', 'cj']).agg(kn=('kn', 'sum'), hit=('n', 'size'), frp=('frp', 'sum'),
                                                 n=('n', 'sum'), nn=('nn', 'sum')).reset_index())
    cd['den'] = den.reindex(cd['dloc']).values
    cd = cd[cd['den'] > 0].reset_index(drop=True)
    cd['V'] = (cd['kn'] / cd['den']).astype('float32')
    cd['y'] = np.minimum(cd['hit'] / cd['den'], 1).astype('float32')
    cd['frpV'] = (cd['frp'] / cd['den']).astype('float32')
    cd['nightf'] = (cd['nn'] / cd['n']).astype('float32')

    Xu = np.zeros((nc, nd, F), 'float32')
    ck = lambda i, j: (np.asarray(i).astype(np.int64) + 500) * 2000 + (np.asarray(j).astype(np.int64) + 1000)
    act_keys = ck(ctx['cells_ci'], ctx['cells_cj'])
    kmap = pd.Series(np.arange(nc), index=act_keys)
    ck_cd = ck(cd['ci'].values, cd['cj'].values)
    m = np.isin(ck_cd, act_keys)
    c_idx, d_idx = kmap.reindex(ck_cd[m]).values, cd['dloc'].values[m] - day_lo
    Xu[c_idx, d_idx, 0] = cd['y'].values[m]
    Xu[c_idx, d_idx, 1] = np.log1p(cd['V'].values[m])
    Xu[c_idx, d_idx, 2] = np.log1p(cd['frpV'].values[m])
    Xu[c_idx, d_idx, 3] = cd['nightf'].values[m]
    parts = []
    for di in (-1, 0, 1):
        for dj in (-1, 0, 1):
            k2 = ck(cd['ci'].values + di, cd['cj'].values + dj)
            mm = np.isin(k2, act_keys)
            parts.append(pd.DataFrame({'k': k2[mm], 'd': cd['dloc'].values[mm] - day_lo, 'y': cd['y'].values[mm]}))
    if parts:
        nb = pd.concat(parts).groupby(['k', 'd'])['y'].sum().reset_index()
        Xu[kmap.reindex(nb['k']).values, nb['d'].values, 4] = nb['y'].values / 9
    DOY, MON = doy_of(days), month_of(days)
    Xu[:, :, 5] = np.sin(2 * np.pi * DOY / 365.25)[None]
    Xu[:, :, 6] = np.cos(2 * np.pi * DOY / 365.25)[None]
    Xu[:, :, 7] = (den.values / ctx['n_vs'])[None]
    Xu[:, :, 8] = ctx['clim'][:, DOY]
    Xu[:, :, 9] = ctx['hclim'][:, MON]
    report['denByDay'] = {str(i2d(d)): int(n) for d, n in zip(days, den.values)}
    report['fireCellDays'] = int(len(cd))
    return Xu, Xu[:, :, 0].copy(), den.values, report
