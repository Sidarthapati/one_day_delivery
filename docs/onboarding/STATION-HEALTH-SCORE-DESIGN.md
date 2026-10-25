# Station Health Score — Design

> Status: **DESIGN / proposal.** No code yet. Source: Knostics KT session (`da_onboarding_knostics.mov`).
> Effort: **small–medium** — most of the raw signals already exist (per-DA scorecards in dispatch, SLA
> pass-rate/breach in M10); the build is to **pick a minimal metric set, aggregate per station + DA, persist a
> weekly rollup, and expose a console**.
> Scope decision (confirmed): build the **minimal set we can compute from data we already have**. Amazon's full
> taxonomy and the Knostics shortlist are recorded below as reference/future, but are **not** in the v1 build.

## 1. Why

Amazon grades each delivery station on a weekly scorecard (~20–25 metrics) and Knostics tracks the same to keep
their stations "green". We want our own **station health score** so ops can see, per station, whether delivery
quality is on target — and drill into the DAs pulling it down. We already compute most of the inputs; today they
are scattered and recomputed on-read with no per-station rollup and no composite rating.

## 2. Reference (from the video)

### 2.1 Amazon "Performance Summary" (live console) — `screens/amazon-performance-summary.jpg`
Weekly, per station. Sections:
- **Overall:** Overall Standing, Safety, Comprehensive Audit, Quality, Team & Customer Experience.
- **Safety:** On-Road Safety Score, Helmet Adherence.
- **Quality:** Overall Quality Score, **DOT (Delivered on Time) Standard**, **DOT Premium**, Undel DPMO,
  C-Return First-Day-Pickup Success.

### 2.2 Amazon "Health Score" workbook — `screens/amazon-health-score-metrics.jpg`
Stations as columns; per-station rows `station_code, time1, DDS Volume, Forecast Volume, Total_GTG`, then ✓/✗
pass-fail % metrics grouped in families:
- **AFN Premium / AFN Standard / ES**, each with **DEA% · LM Miss% · DOT% · DDS% · FDDS% · RDDS%**.
- Plus: CReturn FDPS%, Good Scan Non-Del%, Good Scan Not-Picked%, Invalid Scan%, Post-Attempt CPS%,
  Slot Adherence%, HFR Adherence%.
- Summary rows: **Total Metrics**, **Metric Meeting OP2** (# of metrics meeting the operating-plan target),
  **Health Status %** (metrics-met ÷ total, banded red→green).

### 2.3 Knostics' own curated shortlist (hand-typed in the sheet)
DSR, PDD, MPS, Digital Transaction, COD Transaction, Shipment Aging, Bank Deposit COD, RTS, Return to Seller,
Customer Complaint. (This is the operator's opinion of "the ones that matter" — a useful north star.)

**Model we borrow:** each metric has a **target**; a metric is **met/not-met**; the station's score is
essentially *metrics-met ÷ total*, banded into GREEN/AMBER/RED (Amazon's "Health Status %").

## 3. What we have today

**No** station-health score, scorecard table, composite KPI, or per-station GREEN/AMBER/RED exists. But the raw
signals do:

| Signal | Where | Notes |
|---|---|---|
| **DSR** (attempt success %) | `GET /dispatch/scorecards` → `DaScorecard.attemptSuccessPct` | per-DA, computed live from `dispatch_queue` |
| **On-time / DOT %** | `DaScorecard.onTimePct` | per-DA, completed on/before ETA |
| Stops done/failed/pending, stops-per-hour | `DaScorecard` | per-DA |
| Execution stats | `GET /dispatch/execution` → `DispatchExecutionStats` | per-DA pace, attempt %, completed/failed |
| **SLA pass-rate** | `GET /api/v1/sla/metrics/pass-rate` → `SlaPassRateResponse` | per **city + time window**; `closed/breached/passed/passRate` |
| **SLA breach %** / states | M10 `sla_shipment` (GREEN/AMBER/RED, `breached`), `sla_leg` | per-shipment; escalations `sla_escalation` |
| Location-trust GREEN/AMBER/RED | `GET /dispatch/integrity/das` → `DaIntegritySummary` | anti-fraud, not delivery quality |
| On-time / ageing (merchant/admin) | `orders` `OnTimeStat`, `ShipmentAgeingStats` | not per-station |
| Rolling per-facility snapshot **pattern** | `hub_load_snapshot` (`V7_8`) | the persistence pattern to copy for a rollup |

Key limitation: `SlaShipmentRepository.countClosedBetween/countBreachedBetween` group by **city + window only** —
no per-DA / per-station GROUP BY, and **nothing is persisted** — every read recomputes.

## 4. Gap

1. No **composite** score — the signals exist but aren't combined into one station rating.
2. No **per-station (and per-DA within a station) aggregation** — SLA is per-city; scorecards are per-DA-per-day.
3. No **persisted rollup** — everything recomputes on read; no history/trend, no cheap dashboard.
4. No **console** presenting the station scorecard with GREEN/AMBER/RED bands.

## 5. Proposed design (v1 = minimal set)

### 5.1 The v1 metric set (all from data we already have)

| Metric | Definition | Source | Proposed target (tunable) |
|---|---|---|---|
| **DSR** (Delivery Success Rate) | delivered ÷ attempted | `DaScorecard.attemptSuccessPct` aggregated per station | ≥ 98% |
| **On-Time / DOT %** | delivered on/before promise | `DaScorecard.onTimePct` | ≥ 95% |
| **SLA Pass-Rate %** | closed shipments meeting the 24h promise | M10 `sla_shipment` per station(city) | ≥ 99% |
| **SLA Breach %** | breached ÷ closed | M10 `sla_shipment.breached` | ≤ 1% |
| **RTO / RTS Rate** | return-to-origin / return-to-station ÷ shipments | `orders` (RTO states) | ≤ target |
| **Shipment Ageing** | % of open shipments past age threshold | `orders` `ShipmentAgeingStats` | ≤ target |

Score = (# metrics meeting target ÷ total metrics), banded: **GREEN ≥ 90%, AMBER 70–90%, RED < 70%** (bands
tunable; mirrors Amazon "Health Status %"). Because "station" in our model = **city scope** (there is no station
entity — see onboarding doc), the v1 aggregation key is **city**, with an optional per-DA breakdown within it.

> Reference/future (NOT in v1): PDD adherence, MPS, digital-transaction %, COD-transaction & Bank-Deposit-COD
> (ties into the D3-ix COD settlement work), Customer Complaint (exceptions M11), Safety/Helmet, DPMO, Slot/HFR
> adherence, DEA/DDS/FDDS/RDDS families. Each needs new capture or plumbing; list them in §7 as candidates.

### 5.2 Data — a persisted weekly rollup (copy `hub_load_snapshot`)
New module home: **`sla` (M10)** is the natural owner (it already holds delivery-quality state), or a small new
`scorecard` area in dispatch. Proposed table (new Flyway in the chosen module):

```sql
CREATE TABLE station_health_snapshot (
  id            UUID PRIMARY KEY,
  city_id       UUID NOT NULL,
  period_start  DATE NOT NULL,          -- weekly bucket (or daily)
  period_end    DATE NOT NULL,
  metrics       JSONB NOT NULL,         -- { dsr:{value,target,met}, dot:{…}, slaPass:{…}, … }
  metrics_total INT  NOT NULL,
  metrics_met   INT  NOT NULL,
  health_pct    NUMERIC(5,2) NOT NULL,
  band          VARCHAR(8) NOT NULL,    -- GREEN | AMBER | RED
  computed_at   TIMESTAMPTZ NOT NULL,
  UNIQUE (city_id, period_start, period_end)
);
```
Append-only per period (matches the "nightly/rolling snapshot" invariant). Optional sibling
`da_health_snapshot` (same shape keyed by `da_id`) for the per-DA drill-down.

### 5.3 Compute — a scheduled job
`StationHealthRollupJob` (@Scheduled, weekly + on-demand). It calls the **existing** read APIs/repos —
`DaScorecard` aggregation, `SlaShipmentRepository` counts, `orders` RTO/ageing stats — applies targets, writes
the snapshot rows. No new raw-data capture in v1.

### 5.4 API + console
```
GET /api/v1/station-health?cityId&period            → latest snapshot + per-metric met/target + band
GET /api/v1/station-health/{cityId}/history?from&to → trend
GET /api/v1/station-health/{cityId}/das?period      → per-DA breakdown (worst offenders)
```
Gate STATION_MANAGER (own city) / SUPERVISOR / ADMIN (all), consistent with `SlaDashboardController`. Console
(`oneday-web` station/admin): a scorecard card per metric (value vs target, ✓/✗) + the station band, and a DA
table sorted by contribution to misses — this is the Amazon "Performance Summary" shape.

## 6. Build slices
1. **S1 — rollup core:** table + `StationHealthRollupJob` wiring the existing signals + composite/band logic; unit
   tests on band thresholds and metric-met math.
2. **S2 — API:** the three GETs over the snapshot table (+ live fallback if a period isn't rolled up yet).
3. **S3 — console:** station scorecard + per-DA drill-down.
4. **S4 (later):** add reference/future metrics one at a time as their capture lands (COD/customer-complaint first,
   since D3-ix COD settlement and exceptions M11 already exist).

## 7. Open questions
- **Q-H1:** Aggregation key — city only (our current "station" = city), or do we finally introduce a **station/hub
  entity** so multiple physical stations per city can be scored separately? (Onboarding doc raises the same gap.)
- **Q-H2:** Targets/bands — who owns the numbers above? They should be config, not hard-coded.
- **Q-H3:** Cadence — weekly (Amazon's cadence) vs daily rolling. Proposed weekly + on-demand recompute.
- **Q-H4:** Which "future" metrics get promoted into v2, and in what order? (Proposed: COD-transaction /
  Bank-Deposit-COD and Customer-Complaint first — data already exists.)

## 8. Reference screens
- `screens/amazon-performance-summary.jpg`
- `screens/amazon-health-score-metrics.jpg`
- `screens/contact-sheet-16-32min-amazon-perf-healthscore.png`
