"""Fire probability API (FastAPI).

Run:  ART=artifacts FIRE_SOURCES=sources.json FIRMS_MAP_KEY=... uvicorn app:app --host 0.0.0.0 --port 8000
Endpoints:
  GET  /health                 model + data coverage
  POST /forecast               {bbox, asOf?, days?} -> next 1..7 days P(fire) per 0.5° cell
  GET  /accuracy               backtest: forecasts issued asOf in [from, to] scored against observations
  GET  /observed               observed fire cells (y_soft > 0) on a date
  GET  /sources                configured data-source plugins
  POST /ingest                 pull new days from the plugins, rebuild features, extend the window
"""
from __future__ import annotations
import datetime as dt
import os
import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException, Header, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from fireservice.state import LiveState
from fireservice.sources import load_sources, STD_COLS
from fireservice.metrics import clean_json

ART = os.environ.get('ART', 'artifacts')
SOURCES_FILE = os.environ.get('FIRE_SOURCES', 'sources.json')
INGEST_TOKEN = os.environ.get('INGEST_TOKEN', '')
S = LiveState(ART)
SOURCES = load_sources(SOURCES_FILE) if os.path.exists(SOURCES_FILE) else []

app = FastAPI(title='Fire probability API', version=S.cfg['modelVersion'])
app.add_middleware(CORSMiddleware, allow_origins=['*'], allow_methods=['*'], allow_headers=['*'])


def parse_bbox(bbox: str | None):
    if not bbox:
        return None
    v = [float(x) for x in bbox.split(',')]
    if len(v) != 4 or v[0] > v[2] or v[1] > v[3]:
        raise HTTPException(422, 'bbox must be minLon,minLat,maxLon,maxLat')
    return v


class ForecastRequest(BaseModel):
    bbox: list[float] = Field(..., min_length=4, max_length=4, description='[minLon, minLat, maxLon, maxLat]')
    asOf: dt.date | None = Field(None, description='last observed day; default = latest day in the window')
    days: int = Field(7, ge=1, le=7)


class IngestRequest(BaseModel):
    start: dt.date | None = Field(None, description='first local day to (re)build; default = last day - 2')
    end: dt.date | None = Field(None, description='last local day; default = yesterday (UTC)')


@app.get('/health')
def health():
    return {'status': 'ok', 'modelVersion': S.cfg['modelVersion'], 'dataEnd': str(S.last), 'cells': int(len(S.CI)),
            'asOfRange': [str(S.first_as_of), str(S.last)], 'canIngest': S.can_ingest,
            'sources': [s.name for s in SOURCES],
            'target': 'P(>=1 VIIRS hotspot, NOAA-20-equivalent, in a 0.5deg cell on that local day)'}


@app.post('/forecast')
def forecast(req: ForecastRequest):
    cells = S.cells_in(req.bbox)
    if len(cells) == 0:
        raise HTTPException(404, 'no modelled cells in bbox (cells need >=20 fire-days in 2012-2021)')
    as_of = req.asOf or S.last
    try:
        dates, p, cf, obs = S.predict(cells, as_of)
    except ValueError as e:
        raise HTTPException(422, str(e))
    region, items = [], []
    for h in range(req.days):
        o = obs[:, h]
        region.append({'date': str(dates[h]), 'expectedFireCells': round(float(p[:, h].sum()), 2),
                       'pMean': round(float(p[:, h].mean()), 4), 'pClimMean': round(float(cf[:, h].mean()), 4),
                       'anomaly': round(float(p[:, h].mean() / max(cf[:, h].mean(), 1e-4)), 2),
                       'observedFireCells': None if np.isnan(o).all() else int((o > 0).sum())})
        for k, c in enumerate(cells):
            items.append({'date': str(dates[h]), 'cellId': f'{S.CI[c]}_{S.CJ[c]}',
                          'lat': float(S.LAT[c]), 'lon': float(S.LON[c]),
                          'p': round(float(p[k, h]), 4), 'pClim': round(float(cf[k, h]), 4),
                          'anomaly': round(float(p[k, h] / max(cf[k, h], 1e-4)), 2),
                          'observed': None if np.isnan(o[k]) else round(float(o[k]), 2)})
    return {'modelVersion': S.cfg['modelVersion'], 'asOf': str(as_of), 'cells': int(len(cells)),
            'region': region, 'items': items}


@app.get('/accuracy')
def accuracy(from_: dt.date | None = Query(None, alias='from'), to: dt.date | None = None, bbox: str | None = None):
    cells = S.cells_in(parse_bbox(bbox))
    if len(cells) == 0:
        raise HTTPException(404, 'no modelled cells in bbox')
    to = to or (S.last - dt.timedelta(days=1))
    from_ = from_ or (to - dt.timedelta(days=29))
    try:
        res = S.backtest(cells, from_, to)
    except ValueError as e:
        raise HTTPException(422, str(e))
    res['note'] = ('Brier skill score > 0 and prAuc > prAucClimatology mean the model beats climatology. '
                   'at0_5.accuracy is reported but is dominated by no-fire cells; do not use it as the headline.')
    return clean_json(res)


@app.get('/observed')
def observed(date: dt.date, bbox: str | None = None):
    t = S.t_of(date)
    if t < 0 or t > S.X.shape[1] - 1:
        raise HTTPException(422, f'date must be between {S.first} and {S.last}')
    cells = S.cells_in(parse_bbox(bbox))
    y = S.Y[cells, t]
    return {'date': str(date), 'cells': [{'cellId': f'{S.CI[c]}_{S.CJ[c]}', 'lat': float(S.LAT[c]),
                                          'lon': float(S.LON[c]), 'y': round(float(v), 2)}
                                         for c, v in zip(cells, y) if v > 0]}


@app.get('/sources')
def sources():
    return {'sources': [{'name': s.name, 'kind': s.kind} for s in SOURCES], 'file': SOURCES_FILE}


@app.post('/ingest')
def ingest(req: IngestRequest, x_ingest_token: str | None = Header(None)):
    if INGEST_TOKEN and x_ingest_token != INGEST_TOKEN:
        raise HTTPException(401, 'bad ingest token')
    if not SOURCES:
        raise HTTPException(400, f'no enabled sources in {SOURCES_FILE}')
    end = req.end or (dt.datetime.now(dt.timezone.utc).date() - dt.timedelta(days=1))
    start = req.start or (S.last - dt.timedelta(days=2))     # re-pull 3 days: NRT arrives late
    if start > end:
        raise HTTPException(422, 'start after end')
    frames, per_source = [], {}
    for src in SOURCES:
        try:
            d = src.fetch(start - dt.timedelta(days=1), end + dt.timedelta(days=1))   # UTC margin for local dates
            frames.append(d); per_source[src.name] = int(len(d))
        except Exception as e:                                                    # one bad feed must not kill ingest
            per_source[src.name] = f'error: {e}'
    det = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=STD_COLS)
    try:
        rep = S.ingest(det, start, end)
    except RuntimeError as e:
        raise HTTPException(409, str(e))
    rep['rowsBySource'] = per_source
    return clean_json(rep)
