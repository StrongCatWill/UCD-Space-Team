"""Training-row sampling and window gathering, shared by the GBM baseline and the LSTM (numpy only).

Zero inflation: most cell-weeks have no fire anywhere in the MODIS window. We stratify on the INPUT only
(never on the VIIRS target, which would bias the model): every row whose MODIS window has a detection is kept,
and window-empty rows are sampled at rate s and get weight 1/s. VIIRS-only small fires inside empty windows
stay in proportion, so predicted totals remain unbiased.
"""
import numpy as np

from seq.common import LOOKBACK, LOOKAHEAD, WIN, DYN, weeks_in, time_features

OFFS = np.arange(-(LOOKBACK - 1), LOOKAHEAD + 1)          # window offsets relative to the target week
MODIS_COLS = [i for i, d in enumerate(DYN) if d.startswith("m_")]
COV_COLS = [DYN.index("cov_t"), DYN.index("cov_a")]


def window_active(R, weeks):
    """(C, len(weeks)) bool: any MODIS detection in the window around each week."""
    pos = (R > 0).astype(np.int32)
    cs = np.concatenate([np.zeros((R.shape[0], 1), np.int32), np.cumsum(pos, axis=1, dtype=np.int32)], axis=1)
    lo = np.clip(weeks + OFFS[0], 0, R.shape[1])
    hi = np.clip(weeks + OFFS[-1] + 1, 0, R.shape[1])
    return (cs[:, hi] - cs[:, lo]) > 0


def valid_weeks(years, M, W):
    w = weeks_in(years, W)
    w = w[(w + OFFS[0] >= 0) & (w + OFFS[-1] < W)]
    return w[M[w]]


def sample_rows(R, M, years, rng, empty_ratio=1.0):
    """Return (cells, weeks, weights) for a training split."""
    W = R.shape[1]
    weeks = valid_weeks(years, M, W)
    act = window_active(R, weeks)
    ci, wi = np.nonzero(act)
    n_act = len(ci)
    ce, we = np.nonzero(~act)
    s = min(1.0, empty_ratio * n_act / max(len(ce), 1))
    pick = rng.random(len(ce)) < s
    cells = np.concatenate([ci, ce[pick]]).astype(np.int32)
    wk = weeks[np.concatenate([wi, we[pick]])].astype(np.int32)
    wt = np.concatenate([np.ones(n_act, np.float32), np.full(pick.sum(), 1.0 / s, np.float32)])
    perm = rng.permutation(len(cells))
    return cells[perm], wk[perm], wt[perm], {"n_active": int(n_act), "n_empty_total": int(len(ce)),
                                              "empty_rate": float(s), "n_rows": int(len(cells))}


def gather(X, S, cells, weeks):
    """Sequence inputs (n, WIN, D) and static+time inputs (n, len(STATIC)+2)."""
    seq = X[cells[:, None], weeks[:, None] + OFFS[None, :]]
    st = np.concatenate([S[cells], time_features(weeks)], axis=1)
    return seq.astype(np.float32, copy=False), st.astype(np.float32, copy=False)


def flat_features(seq, st):
    """GBM design: MODIS features at every window step + coverage at the target week + static/time."""
    n = seq.shape[0]
    m = seq[:, :, MODIS_COLS].reshape(n, -1)
    cov = seq[:, LOOKBACK - 1, :][:, COV_COLS]
    # window summaries the trees would otherwise have to rebuild
    mn = seq[:, :, DYN.index("m_n")]
    summ = np.stack([np.log1p(np.expm1(mn).sum(1)), (mn > 0).sum(1).astype(np.float32)], axis=1)
    return np.concatenate([m, cov, summ, st], axis=1)


def dense_rows(C, weeks):
    cells = np.repeat(np.arange(C, dtype=np.int32), len(weeks))
    wk = np.tile(weeks.astype(np.int32), C)
    return cells, wk


assert WIN == len(OFFS)
