# fire-backend API — 프론트엔드 사용 가이드

Base URL: `http://localhost:8080` (dev). CORS 허용: `http://localhost:5173`, `http://localhost:3000`.

모든 bbox는 `[minLon, minLat, maxLon, maxLat]` (서, 남, 동, 북). 예시 bbox는 캘리포니아: `[-124.5, 32.5, -114, 42]`.

> **예측값의 의미 (중요)**: `p`는 "0.5° 셀에서 VIIRS 위성 1대 상당이 그날 hotspot을 1개 이상 **탐지**할 확률"이다.
> 발화(ignition) 확률이 아니다. UI 문구에 "fire detection probability"로 표기할 것.

---

## 1. POST /api/forecast — 1~7일 화재 탐지 확률

```bash
curl -s -X POST http://localhost:8080/api/forecast \
  -H 'Content-Type: application/json' \
  -d '{"bbox":[-124.5,32.5,-114,42],"days":3}'
```

Request body: `{bbox: number[4], asOf?: "YYYY-MM-DD", days?: 1~7}` (asOf 생략 = 최신 데이터일, days 기본 7)

Response (실측 예시, 2026-11-14):

```json
{
  "modelVersion": "fire-lstm-v1",
  "asOf": "2026-11-07",
  "cells": 206,
  "source": "fallback-precomputed",
  "region": [
    {"date":"2026-11-08","expectedFireCells":16.54,"pMean":0.0803,
     "pClimMean":0.0744,"anomaly":1.08,"observedFireCells":null}
  ],
  "items": [
    {"date":"2026-11-08","cellId":"65_-237","lat":32.75,"lon":-118.25,
     "p":0.0001,"pClim":0.0,"anomaly":1.39,"observed":null}
  ]
}
```

- `source`: `"model-api"` = Python 모델 실시간 추론 / `"fallback-precomputed"` = Python 서비스가 죽어서 미리 계산된 값 제공. **UI에 배지로 노출 권장.**
- `region[]`: 날짜별 지역 요약. `expectedFireCells` = 그날 기대 화재 셀 수(Σp), `anomaly` = pMean/pClimMean (1.0 = 평년 수준).
- `items[]`: 셀×날짜. `p` = 예측 탐지 확률, `pClim` = 기후값(해당 날짜의 역사적 평균), `observed` = 실제 관측(미래라서 아직 모르면 `null`).
- 에러: bbox 안에 모델링된 셀이 없으면 404, asOf가 데이터 범위 밖이면 422.

## 2. GET /api/calendar — 연×월 화재 달력

```bash
curl -s "http://localhost:8080/api/calendar?bbox=-124.5,32.5,-114,42"
```

Response (실측 예시 요약):

```json
{
  "bbox": [-124.5,32.5,-114.0,42.0],
  "cellCount": 313,
  "unit": "NOAA-20-equivalent hotspots per month",
  "years": [2000, "...", 2026],
  "matrix": [[null,null,"...",2415.4,700.2], ["..."]],
  "monthlyMedian": ["..12개.."],
  "latestCompleteMonth": {"month":"2026-10","value":1804.0,"historicalMedian":3329.9,
                          "ratioToMedian":0.54,"percentileRank":28.0},
  "peakMonth": 8,
  "topYears": [{"year":2020,"total":224814.5},{"year":2021,"total":189406.5},{"year":2018,"total":87327.5}],
  "viirsNativeFrom": "2012-02",
  "note": "..."
}
```

- `matrix[yearIdx][month-1]` = 보정(harmonized) hotspot 수. `null` = 데이터 없는 달(2000-11 이전 등).
- 값은 **소수(fractional)일 수 있음** — MODIS를 VIIRS 상당으로 보정한 값이라 정수가 아님.
- `viirsNativeFrom` 이전은 MODIS 보정값, 이후는 VIIRS 실측 기반 → 히트맵에 경계 표시 가능.
- `peakMonth`: 1~12. `latestCompleteMonth.ratioToMedian`: 1.0 = 평년, `percentileRank`: 0~100.

## 3. GET /api/calendar.csv — CSV 다운로드

```bash
curl -sO -J "http://localhost:8080/api/calendar.csv?bbox=-124.5,32.5,-114,42"
```

`Content-Disposition: attachment; filename="burning_calendar.csv"` → `<a href>` 또는 `window.open`으로 바로 다운로드 가능.

## 3-1. GET /api/hotspots.csv — 검색 영역의 hotspot CSV 다운로드

```bash
curl -sO -J "http://localhost:8080/api/hotspots.csv?bbox=-124.5,32.5,-114,42&from=2020-08&to=2020-09"
```

- `from`, `to`: `YYYY-MM`, 둘 다 선택(생략 시 전체 2000-11 ~ 최신월). `from > to`이거나 형식이 틀리면 400.
- 컬럼: `month,cellId,lat,lon,hotspots_noaa20_equivalent,source` — bbox 안 0.5° 셀 × 월 중 **0이 아닌 것만**.
- `source`: `MODISxk`(MODIS 보정값) / `VIIRS` / `-partial` 접미사 = 아직 다 안 끝난 달.
- 개별 탐지점(위경도 점) 원본이 아니라 **0.5° 셀 단위로 보정된 월별 개수**다. lat/lon은 셀 중심.

```typescript
export function hotspotsCsvUrl(bbox: Bbox, from?: string, to?: string): string {
  const q = new URLSearchParams({ bbox: bbox.join(","), ...(from && { from }), ...(to && { to }) });
  return `${BASE}/api/hotspots.csv?${q}`;
}
// <a href={hotspotsCsvUrl(CA, "2020-08", "2020-09")} download>Download CSV</a>
```

## 4. GET /api/accuracy — 백테스트 (Python 프록시)

```bash
curl -s "http://localhost:8080/api/accuracy?from=2026-10-01&to=2026-10-30&bbox=-124.5,32.5,-114,42"
```

응답: `{asOfFrom, asOfTo, cells, leads:[{lead, n, brier, brierClimatology, brierSkillScore, prAuc, prAucClimatology, at0_5:{...}}], daily:[...], note}`.
`brierSkillScore > 0`이고 `prAuc > prAucClimatology`면 모델이 기후값보다 낫다는 뜻. `at0_5.accuracy`는 무화재 셀이 지배하므로 헤드라인 지표로 쓰지 말 것. **Python 서비스가 꺼져 있으면 502.**

## 5. GET /api/observed — 관측된 화재 셀 (Python 프록시)

```bash
curl -s "http://localhost:8080/api/observed?date=2026-11-01&bbox=-124.5,32.5,-114,42"
```

응답: `{date, cells:[{cellId, lat, lon, y}]}` — `y > 0`인 셀만. 지도에서 예측 vs 실제 비교 오버레이용. Python 꺼져 있으면 502.

## 6. GET /api/health

```bash
curl -s http://localhost:8080/api/health
```

```json
{"backend":"ok","fallbackForecastAsOf":"2026-11-07",
 "modelApi":{"status":"down","error":"..."}}
```

`modelApi.status`가 `"ok"`가 아니면 forecast는 fallback 모드로 동작 중.

---

## TypeScript (fetch) 예시

```typescript
// src/data/fireApi.ts
const BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export type Bbox = [number, number, number, number]; // [minLon, minLat, maxLon, maxLat]

export interface RegionDay {
  date: string;                       // "YYYY-MM-DD"
  expectedFireCells: number;
  pMean: number;
  pClimMean: number;
  anomaly: number;                    // pMean / pClimMean, 1.0 = normal
  observedFireCells: number | null;   // null until observed
}

export interface CellForecast {
  date: string;
  cellId: string;                     // "{ci}_{cj}"
  lat: number;                        // cell center
  lon: number;
  p: number;                          // P(>=1 hotspot detected), NOT ignition prob
  pClim: number;
  anomaly: number;
  observed: number | null;
}

export interface ForecastResponse {
  modelVersion: string;
  asOf: string;
  cells: number;
  source: "model-api" | "fallback-precomputed";
  region: RegionDay[];
  items: CellForecast[];
}

export interface CalendarResponse {
  bbox: Bbox;
  cellCount: number;
  unit: string;
  years: number[];
  matrix: (number | null)[][];        // matrix[yearIdx][month-1], fractional
  monthlyMedian: (number | null)[];   // 12 entries
  latestCompleteMonth: {
    month: string;                    // "YYYY-MM"
    value: number;
    historicalMedian: number;
    ratioToMedian: number;
    percentileRank: number;           // 0..100
  };
  peakMonth: number;                  // 1..12
  topYears: { year: number; total: number }[];
  viirsNativeFrom: string;            // "YYYY-MM"; before = harmonized MODIS
  note: string;
}

async function ok<T>(r: Response): Promise<T> {
  if (!r.ok) throw new Error(`${r.status}: ${await r.text()}`);
  return r.json() as Promise<T>;
}

export async function fetchForecast(
  bbox: Bbox, days = 7, asOf?: string, signal?: AbortSignal,
): Promise<ForecastResponse> {
  const r = await fetch(`${BASE}/api/forecast`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ bbox, days, ...(asOf && { asOf }) }),
    signal,
  });
  return ok<ForecastResponse>(r);
}

export async function fetchCalendar(bbox: Bbox, signal?: AbortSignal): Promise<CalendarResponse> {
  const r = await fetch(`${BASE}/api/calendar?bbox=${bbox.join(",")}`, { signal });
  return ok<CalendarResponse>(r);
}

export function calendarCsvUrl(bbox: Bbox): string {
  return `${BASE}/api/calendar.csv?bbox=${bbox.join(",")}`;
}

export async function fetchObserved(date: string, bbox?: Bbox) {
  const q = new URLSearchParams({ date, ...(bbox && { bbox: bbox.join(",") }) });
  return ok<{ date: string; cells: { cellId: string; lat: number; lon: number; y: number }[] }>(
    await fetch(`${BASE}/api/observed?${q}`),
  );
}
```

```typescript
// 사용 예
const CA: Bbox = [-124.5, 32.5, -114, 42];
const fc = await fetchForecast(CA, 3);
if (fc.source === "fallback-precomputed") showBadge("precomputed (model offline)");
const cal = await fetchCalendar(CA);
// heatmap: cal.matrix[yi][mi], 눈금은 cal.years / 1..12월
```

## frontend-data-layer.md 질문에 대한 답

- **Timestamp/timezone**: 모든 날짜는 셀의 **local day** 기준 `"YYYY-MM-DD"` 문자열. 시각/타임존 없음.
- **bbox 필드명/antimeridian**: `[minLon, minLat, maxLon, maxLat]` 배열(POST) 또는 `"w,s,e,n"` 쉼표 문자열(GET). antimeridian을 걸치는 bbox는 **미지원** (minLon ≤ maxLon 가정).
- **센서/단위**: 달력 단위는 `"NOAA-20-equivalent hotspots per month"` — MODIS·VIIRS를 NOAA-20 VIIRS 상당으로 보정한 값. 응답에 센서 구분은 없고 `viirsNativeFrom`으로 보정/실측 경계만 제공.
- **count가 소수인가**: 예. 보정 계수 곱이라 fractional. confidence 어휘는 노출하지 않음.
