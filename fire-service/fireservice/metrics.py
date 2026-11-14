"""Forecast verification metrics (numpy only).
y is the soft label (0, 0.5, 1); 'fire' events for PR-AUC / accuracy are y > 0."""
from __future__ import annotations
import numpy as np


def brier(p, y) -> float:
    return float(np.mean((p - y) ** 2))


def average_precision(score, event) -> float:
    """Area under the precision-recall curve (step-wise, ties grouped) — same definition as
    sklearn.metrics.average_precision_score."""
    score, event = np.asarray(score).ravel(), np.asarray(event).ravel().astype(bool)
    P = event.sum()
    if P == 0:
        return float('nan')
    order = np.argsort(-score, kind='mergesort')
    s, e = score[order], event[order]
    tp = np.cumsum(e)
    last = np.r_[np.nonzero(np.diff(s))[0], len(s) - 1]          # last index of each tie group
    tp, n = tp[last], last + 1
    precision, recall = tp / n, tp / P
    return float(np.sum(np.diff(np.r_[0.0, recall]) * precision))


def at_threshold(p, event, thr: float) -> dict:
    pred, event = p >= thr, event.astype(bool)
    tp = int((pred & event).sum()); fp = int((pred & ~event).sum())
    fn = int((~pred & event).sum()); tn = int((~pred & ~event).sum())
    prec = tp / (tp + fp) if tp + fp else float('nan')
    rec = tp / (tp + fn) if tp + fn else float('nan')
    f1 = 2 * prec * rec / (prec + rec) if tp else 0.0
    return {'threshold': thr, 'accuracy': (tp + tn) / max(tp + fp + fn + tn, 1), 'precision': prec,
            'recall': rec, 'f1': f1, 'tp': tp, 'fp': fp, 'fn': fn, 'tn': tn}


def summarize(p, clim, y) -> dict:
    event = y > 0
    bm, bc = brier(p, y), brier(clim, y)
    return {
        'n': int(y.size), 'observedFireRate': float(event.mean()), 'meanP': float(p.mean()),
        'brier': bm, 'brierClimatology': bc, 'brierSkillScore': 1 - bm / bc if bc > 0 else float('nan'),
        'prAuc': average_precision(p, event), 'prAucClimatology': average_precision(clim, event),
        'at0_5': at_threshold(p, event, 0.5),
    }


def clean_json(o):
    """NaN/inf are not valid JSON -> None."""
    if isinstance(o, dict):
        return {k: clean_json(v) for k, v in o.items()}
    if isinstance(o, list):
        return [clean_json(v) for v in o]
    if isinstance(o, float) and not np.isfinite(o):
        return None
    return o
