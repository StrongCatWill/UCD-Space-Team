"""Rolling feature window + model. Loaded from the Colab artifacts; updated by /ingest."""
from __future__ import annotations
import datetime as dt
import json
import os
import threading
import numpy as np
import torch
from firemodel import FireLSTM
from .features import clean, build_days, d2i, i2d, doy_of
from . import metrics


class LiveState:
    def __init__(self, art: str):
        self.art = art
        self.cfg = json.load(open(f'{art}/config.json'))
        live = f'{art}/state_live.npz'
        R = np.load(live if os.path.exists(live) else f'{art}/recent.npz')
        self.X, self.Y = R['X'].astype('float32'), R['Y'].astype('float32')     # X is scaled
        self.CLIM, self.ST = R['clim'].astype('float32'), R['static'].astype('float32')
        self.CI, self.CJ = R['ci'], R['cj']
        self.first = dt.date.fromisoformat(str(R['first_day']))
        self.R_DAYS = self.X.shape[1]
        ing = f'{art}/ingest_state.npz'
        self.can_ingest = os.path.exists(ing)
        if self.can_ingest:
            S = np.load(ing)
            self.mask_keys, self.HCLIM = S['mask_keys'], S['hclim'].astype('float32')
        self.MU = np.asarray(self.cfg['mu'], 'float32'); self.SD = np.asarray(self.cfg['sd'], 'float32')
        self.G, self.L, self.H = self.cfg['grid'], self.cfg['lookback'], self.cfg['horizon']
        self.TEMP = self.cfg['temperature']
        self.LAT, self.LON = (self.CI + .5) * self.G, (self.CJ + .5) * self.G
        self.k = {s: np.r_[1.0, np.asarray(v, 'float32')].astype('float32') for s, v in self.cfg['kViirs'].items()}
        self.k.update({s: np.r_[1.0, np.asarray(v, 'float32')].astype('float32')
                       for s, v in self.cfg.get('kViirsExtra', {}).items()})
        torch.set_num_threads(max(1, min(4, os.cpu_count() or 1)))
        self.model = FireLSTM(len(self.cfg['features']), **self.cfg['model'])
        self.model.load_state_dict(torch.load(f'{art}/model.pt', map_location='cpu')); self.model.eval()
        self.lock = threading.RLock()

    # ---------- dates ----------
    @property
    def last(self) -> dt.date: return self.first + dt.timedelta(days=self.X.shape[1] - 1)
    @property
    def first_as_of(self) -> dt.date: return self.first + dt.timedelta(days=self.L - 1)
    def t_of(self, d: dt.date) -> int: return (d - self.first).days

    def cells_in(self, bbox) -> np.ndarray:
        if bbox is None:
            return np.arange(len(self.CI))
        x0, y0, x1, y1 = bbox
        # half-open [min, max) like the backend: a 0.5-degree ROI always selects exactly one cell
        return np.where((self.LON >= x0) & (self.LON < x1) & (self.LAT >= y0) & (self.LAT < y1))[0]

    # ---------- inference ----------
    def predict(self, cells: np.ndarray, as_of: dt.date):
        with self.lock:
            t = self.t_of(as_of)
            if t < self.L - 1 or t > self.X.shape[1] - 1:
                raise ValueError(f'asOf must be between {self.first_as_of} and {self.last}')
            dates = [as_of + dt.timedelta(days=h) for h in range(1, self.H + 1)]
            cf = self.CLIM[cells][:, [d.timetuple().tm_yday - 1 for d in dates]]
            x = torch.from_numpy(np.ascontiguousarray(self.X[cells, t - self.L + 1:t + 1]))
            with torch.no_grad():
                z = self.model(x, torch.from_numpy(self.ST[cells]), torch.from_numpy(cf))
            p = torch.sigmoid(z / self.TEMP).numpy()
            obs = np.full_like(p, np.nan)
            for h in range(self.H):
                if t + h + 1 <= self.X.shape[1] - 1:
                    obs[:, h] = self.Y[cells, t + h + 1]
            return dates, p, cf, obs

    def backtest(self, cells: np.ndarray, d_from: dt.date, d_to: dt.date) -> dict:
        """Issue a forecast for every asOf in [d_from, d_to] and score it against what was observed."""
        d_from = max(d_from, self.first_as_of); d_to = min(d_to, self.last - dt.timedelta(days=1))
        if d_from > d_to:
            raise ValueError('no asOf dates with observed outcomes in range')
        P, C, Yo, daily = [], [], [], []
        d = d_from
        while d <= d_to:
            dates, p, cf, obs = self.predict(cells, d)
            P.append(p); C.append(cf); Yo.append(obs)
            daily.append({'date': str(dates[0]), 'expectedFireCells': float(p[:, 0].sum()),
                          'climatologyExpected': float(cf[:, 0].sum()), 'observedFireCells': float((obs[:, 0] > 0).sum())})
            d += dt.timedelta(days=1)
        P, C, Yo = np.stack(P), np.stack(C), np.stack(Yo)          # (nAsOf, cells, H)
        leads = []
        for h in range(self.H):
            ok = ~np.isnan(Yo[:, :, h])
            if ok.sum() == 0:
                continue
            leads.append({'lead': h + 1, **metrics.summarize(P[:, :, h][ok], C[:, :, h][ok], Yo[:, :, h][ok])})
        return {'asOfFrom': str(d_from), 'asOfTo': str(d_to), 'cells': int(len(cells)), 'leads': leads, 'daily': daily}

    # ---------- ingestion ----------
    def ctx(self) -> dict:
        return {'cells_ci': self.CI, 'cells_cj': self.CJ, 'clim': self.CLIM, 'hclim': self.HCLIM, 'k': self.k,
                'n_vs': len(self.cfg['viirsSats']), 'features': self.cfg['features']}

    def ingest(self, det, day_lo: dt.date, day_hi: dt.date) -> dict:
        """det: standardized detections covering UTC days [day_lo-1, day_hi+1]. Rebuilds local days
        [day_lo, day_hi] (overwrites what was there), extends the window, trims to R_DAYS, saves."""
        if not self.can_ingest:
            raise RuntimeError('artifacts/ingest_state.npz missing — run build_ingest_state.py first')
        with self.lock:
            lo = max(day_lo, self.first)
            if lo > self.last + dt.timedelta(days=1):
                lo = self.last + dt.timedelta(days=1)          # never leave a hole in the window
            det = clean(det, self.mask_keys, self.G)
            Xu, Y, den, rep = build_days(det, d2i(lo), d2i(day_hi), self.ctx())
            # do not extend the window into days with no VIIRS data yet (they would look like "no fire")
            has = np.nonzero(den > 0)[0]
            if len(has) == 0:
                rep.update(updated=False, reason='no VIIRS detections in range'); return rep
            keep = has[-1] + 1
            Xs = (Xu[:, :keep] - self.MU) / self.SD
            Y, hi = Y[:, :keep], lo + dt.timedelta(days=int(keep) - 1)
            t_lo, t_hi = self.t_of(lo), self.t_of(hi)
            if t_hi > self.X.shape[1] - 1:                     # grow
                grow = t_hi - (self.X.shape[1] - 1)
                self.X = np.concatenate([self.X, np.zeros((self.X.shape[0], grow, self.X.shape[2]), 'float32')], 1)
                self.Y = np.concatenate([self.Y, np.zeros((self.Y.shape[0], grow), 'float32')], 1)
            self.X[:, t_lo:t_hi + 1], self.Y[:, t_lo:t_hi + 1] = Xs, Y
            if self.X.shape[1] > self.R_DAYS:                  # trim oldest
                cut = self.X.shape[1] - self.R_DAYS
                self.X, self.Y = self.X[:, cut:], self.Y[:, cut:]
                self.first += dt.timedelta(days=cut)
            self.save()
            rep.update(updated=True, from_=str(lo), to=str(hi), newLastDay=str(self.last))
            return rep

    def save(self):
        np.savez_compressed(f'{self.art}/state_live.npz', X=self.X, Y=self.Y, clim=self.CLIM, static=self.ST,
                            ci=self.CI, cj=self.CJ, first_day=str(self.first))
