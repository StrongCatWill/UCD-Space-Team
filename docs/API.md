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

---

## 7. GET /api/activity — Historical Monthly Activity (프론트 `MonthlyActivity`)

```bash
curl -s "http://localhost:8080/api/activity?bbox=-124.5,32.5,-114,42"
```

```json
{"bbox":[-124.5,32.5,-114.0,42.0],"cellCount":313,"unit":"NOAA-20-equivalent hotspots per month",
 "viirsNativeFrom":"2012-02","lastCompleteMonth":"2026-10",
 "monthlyMean":[714.7,819.9,945.9,875.1,866.0,2564.9,7690.7,17260.9,13015.5,5229.2,2112.9,1188.8],
 "monthlyMedian":[...12개...],
 "records":[{"year":2026,"month":11,"harmonized":337.0,"modis":null,"viirs":337.0,
             "complete":false,"dailyMean":48.14,"source":"VIIRS"}, "..."]}
```

- `records[]`는 프론트 `MonthlyActivity`와 같은 필드 + `dailyMean`, `source`. 2000-11부터 313개월.
- `harmonized` = 월 합계, `dailyMean` = 그 달 데이터가 있는 날 수로 나눈 하루 평균(미완성 달 비교용).
- `monthlyMean[m-1]` = 그 달(1~12월)의 전 기간 평균, **완성된 달만** 사용. `/api/calendar`에도 `monthlyMean`이 추가됨.
- `modis`는 항상 `null`(원본 MODIS 개수는 제공하지 않음), `viirs`는 VIIRS 실측 구간(2012-02~)에서만 값이 있음.

## 8. GET /api/outlook — 장기 월별 전망 (프론트 `PredictionDashboardData`)

```bash
curl -s "http://localhost:8080/api/outlook?bbox=-124.5,32.5,-114,42&months=12"
```

필드 이름이 프론트 `PredictionDashboardData`와 같다: `sensorMode, unit, points, risks, highRiskMonth, insights, modelConfidence, isMock` (+ `method, bbox, cellCount, note`).

- `points[]`: `phase:"historical"`(관측, 마지막 완성월까지) + `phase:"forecast"`(그 다음 `months`개월, 1~12).
- **forecast 계산 방법 = climatology**: 이 AOI에서 같은 달(예: 과거 모든 8월)의 분포. `forecastMedian`=중앙값, `lower80/upper80`=10/90 백분위, `lower95/upper95`=2.5/97.5 백분위. LSTM이 아니다(`method:"climatology"`). 일 단위 1~7일 탐지 확률은 `/api/forecast`(LSTM).
- `risks[].score` 0~100 = 그 달 중앙값 ÷ 이 AOI의 가장 바쁜 달 중앙값. `level`: ≥85 extreme, ≥60 high, ≥30 moderate, 그 외 low.
- `insights.nextThreeMonthsChangePercent` = 다음 3개월 중앙값 합 vs 1년 전 같은 3개월 실측 합 (%). `longTermTrendPercent` = 최근 5년 평균 연간 합 vs 처음 5년 (%). 계산 불가면 `null`.
- `modelConfidence.scorePercent` = 과거 각 달을 빼고 나머지 해로 만든 80% 구간에 실제 값이 들어간 비율(leave-one-year-out). 80이면 구간이 잘 맞는 것.
- 단위는 원 개수(hotspots/month). 프론트 mock은 "thousand hotspots"였으니 표시할 때 /1000.

```typescript
// AOI → bbox
const bboxOf = (aoi: AreaOfInterest) =>
  [aoi.bounds.west, aoi.bounds.south, aoi.bounds.east, aoi.bounds.north].join(",");

export class HttpPredictionRepository implements PredictionRepository {
  async getPredictionDashboard(q: PredictionQuery): Promise<PredictionDashboardData> {
    const r = await fetch(`${BASE}/api/outlook?bbox=${bboxOf(q.aoi)}&months=${q.forecastMonths}`);
    if (!r.ok) throw new Error(`${r.status}: ${await r.text()}`);
    return r.json();   // nullable: insights.*Percent, modelConfidence.scorePercent
  }
}

export class HttpHistoricalWildfireRepository /* implements HistoricalWildfireRepository */ {
  async getMonthlyActivity(aoi: AreaOfInterest): Promise<MonthlyActivity[]> {
    const r = await fetch(`${BASE}/api/activity?bbox=${bboxOf(aoi)}`);
    if (!r.ok) throw new Error(`${r.status}: ${await r.text()}`);
    return (await r.json()).records;
  }
}
```

## 배포 시 설정 (환경변수)

| 변수 | 기본값 | 의미 |
|---|---|---|
| `PORT` | 8080 | 서버 포트 (호스팅 플랫폼이 넣어줌) |
| `FIRE_MODEL_API_URL` | http://localhost:8000 | Python fire-service 주소 |
| `FIRE_CORS_ORIGINS` | localhost:5173,3000 | 쉼표로 구분. **배포된 프론트 주소를 꼭 추가** |
| `FIRE_REFRESH_ENABLED` | true | 매일 06:30 FIRMS 자동 갱신 |
| `INGEST_TOKEN` | (빈 값) | fire-service와 같은 값이어야 갱신 가능 |
| `FIRE_ADMIN_TOKEN` | (빈 값) | `POST /api/admin/refresh`에 `X-Admin-Token` 헤더로 필요. 비우면 엔드포인트 비활성(403) |

## 9. GET /api/hotspots — 지도용 셀 사각형 (기본)

```bash
curl -s "http://localhost:8080/api/hotspots?bbox=-124.5,32.5,-114,42&from=2020-08&to=2020-09"
```

```json
{"bbox":[...],"from":"2020-08","to":"2020-09","unit":"NOAA-20-equivalent hotspots in range","cellSize":0.5,
 "maxHotspots":26493.0,
 "cells":[{"cellId":"65_-235","lat":32.75,"lon":-117.25,"bounds":[-117.5,32.5,-117.0,33.0],
           "hotspots":4.5,"monthsWithFire":2}]}
```

- 셀마다 `bounds`(=[west,south,east,north])로 **색칠된 사각형**을 그린다. 색은 `hotspots / maxHotspots`.
- `from`/`to`는 YYYY-MM, 생략 시 전체 기록. 기간 안 합계가 0인 셀은 빠진다.
- **ROI 규칙**: 셀 중심이 `[min, max)` 안에 있으면 포함. 0.5° ROI는 항상 **정확히 셀 1개**(가장 많이 겹치는 셀)를 고른다. 화면과 숫자를 맞추려면 ROI를 0.5° 격자(x.0 / x.5)에 맞춰 스냅하는 것을 권장.

## 10. GET /api/hotspots/points — 점 단위 (선택 옵션)

```bash
curl -s "http://localhost:8080/api/hotspots/points?bbox=-120.5,37.0,-120.0,37.5&date=2026-11-13&days=3"
```

- FIRMS에서 **실시간으로** 받은 VIIRS NRT 원본 탐지점. **보정(harmonized) 값이 아니다.** 최근 날짜만(`days` 1~5, `date` 기본 = 어제 UTC).
- 응답: `{bbox, from, to, source, note, points:[{lat, lon, date, hhmm, sat, confidence, frp}]}` — `confidence` 0/50/100 = low/nominal/high.
- fire-service에 FIRMS MAP_KEY가 있어야 한다. 없거나 FIRMS 오류면 502.

## 11. 보정값(anomaly)과 데이터 범위(coverage) — 2026-11-15 추가

**`/api/activity`의 `records[]`에 필드 3개 추가**
- `median`: 같은 달(예: 모든 8월)의 중앙값. **그 해는 빼고** 계산한다(leave-one-year-out). 완성된 달만 사용.
- `anomaly` = `harmonized - median` (절대 차이, 히트맵의 anomaly 모드에 사용)
- `anomalyPercent` = `anomaly / median × 100`. `median`이 0이면 `null` (정의 불가).
- 아직 덜 끝난 달(`complete:false`)은 세 값 모두 `null`.
- 재학습이 필요 없다: `harmonized`가 이미 센서 보정(MODIS→VIIRS 상당)된 값이고, 이건 그 위의 통계다.

**`coverage`: `"inside"` | `"outside"`**
- `/api/activity`, `/api/calendar`, `/api/outlook`, `/api/hotspots` 응답에 들어간다.
- ROI에서 1° 안에 데이터 셀이 하나도 없으면 `"outside"` = 프로토타입 범위(미국) 밖. `"inside"`인데 `cellCount: 0`이면 그 지역은 **진짜 0건**.
- 프론트: `coverage === "outside"`면 "This prototype covers the United States" 표시.
- `POST /api/forecast`, `GET /api/hotspots/points`는 범위 밖이면 **404**와 메시지를 준다.
- 예측에서 모델이 거절한 요청(셀 없음 404, asOf 범위 밖 422)은 이제 그대로 전달된다. 이전에는 fallback 응답으로 잘못 바뀌었다.
