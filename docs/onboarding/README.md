# DA Onboarding, Station Health Score & Attendance — Design Set

Three feature designs derived from a ~90-minute Knostics KT session (`da_onboarding_knostics.mov`) that walked
through how **Amazon** and their delivery partner **Knostics** run delivery-associate (DA) onboarding, station
performance scoring, and geofenced attendance. This folder is **documentation only** — the reference flows, our
current state, the gaps, and a proposed build for each. No code has been written yet.

## The three docs

| Doc | Feature | Effort | One-line gap |
|---|---|---|---|
| [DA-ONBOARDING-UTILITY-DESIGN.md](DA-ONBOARDING-UTILITY-DESIGN.md) | Self-service DA onboarding utility | **Large** | We create a DA with one admin POST and no verification; need a multi-step candidate wizard + doc upload + **mocked per-check BGV (IDfy-style)** + approval queue → `DaProfile` + employee-ID. |
| [STATION-HEALTH-SCORE-DESIGN.md](STATION-HEALTH-SCORE-DESIGN.md) | Station health scorecard | **Small–Med** | The raw signals exist (DSR, on-time, SLA pass/breach) but there's no composite score, no per-station rollup, no console. v1 = **minimal set from data we already have** + weekly persisted rollup. |
| [ATTENDANCE-PHOTO-CAPTURE-DESIGN.md](ATTENDANCE-PHOTO-CAPTURE-DESIGN.md) | Attendance **selfie** capture | **Small** | Geofence + manager approval already exist; the only gap is capturing a **login/logout selfie** — reuse the existing R2 presign/upload plumbing. |

## Confirmed scope decisions
- **Station health = minimal set we can compute today** (DSR, On-Time/DOT, SLA pass-rate + breach, RTO/RTS,
  shipment-ageing). Amazon's full taxonomy + Knostics' shortlist are recorded as future/reference only.
- **DA onboarding = full self-service** (invite link, wizard, docs, e-agreement, training, per-check BGV mock,
  approval queue).
- Reference **screens are committed** under `screens/` and linked from each doc.

## Key reusable scaffolding already in the codebase (why two of three are cheap)
- **B2B onboarding + `KycPort`/`SandboxKycAdapter`** (auth M1) — the exact multi-step-approval + mocked-vendor
  pattern to clone for DA onboarding and for the `BgvPort` (IDfy mock).
- **`ObjectStoragePort` (R2) + parcel-measurement presign→upload→submit** — the doc-upload / selfie-upload plumbing.
- **`DaScorecard` (`/dispatch/scorecards`) + M10 SLA pass-rate/breach + `hub_load_snapshot`** — the metrics and the
  rollup-table pattern for the station health score.
- **Existing geofenced attendance** (dispatch M5 `AttendanceController` + manager approval) — needs only the photo.

## Recurring cross-cutting question
Both onboarding and station-health hit the same limitation: **there is no station/hub entity** — "station" is a
city scope, and DA territory is a per-day H3 hex. Amazon/Knostics assign each DA a **primary station** and score
**per station**. Deciding whether to introduce a real station/hub entity should be made once, for both features.

## Reference screens (`screens/`)
| File | What it shows |
|---|---|
| `amazon-onboarding-funnel.jpg` | Amazon People › Onboarding (S1/S2/S3 stages, progress %, action items) |
| `amazon-da-detail-onboarding.jpg` | Amazon DA detail — "4 of 19 Completed" tasks (Assoc. Settings, DL, Agreement, Videos/Training) |
| `idfy-bgv-dashboard.jpg` | IDfy BGV dashboard (Total / In-Progress / Insufficient / Completed → Green/Amber/Red) |
| `idfy-candidate-profile.jpg` | IDfy candidate — per-check cards (PAN, DL, Address, Database, eFIR, Police) |
| `amazon-performance-summary.jpg` | Amazon station Performance Summary (Overall/Safety/Quality, DOT) |
| `amazon-health-score-metrics.jpg` | Amazon "Health Score" workbook (metric families, Health Status %) |
| `knostics-partner-details-documents.jpg` | Knostics "Partner Details" wizard — Documents To Verify (Aadhaar/PAN images) |
| `knostics-manage-attendance.jpg` | Knostics Manage Attendance table (Login Time, Radius InDoor, Login Lat/Long) |
| `knostics-edit-partner-attendance-selfie.jpg` | Knostics Edit Partner Attendance — **Login/Logout selfie** + Approved By |
| `contact-sheet-16-32min-amazon-perf-healthscore.png` | Contact sheet: Amazon Performance + Health Score section |
| `contact-sheet-32-48min-healthscore-partner-details.png` | Contact sheet: Health Score + Knostics Partner Details |
| `contact-sheet-48-64min-attendance-van-cod.png` | Contact sheet: Attendance + Van + COD reconciliation |

> Method note: the 14 GB / 90-min recording was decomposed into 91 distinct screens via keyframe-only decode +
> scene-change (pixel-diff) dedup, then full-resolution crops of the shared window were transcribed screen by
> screen. The full crop set + 20s-cadence contact sheets were used to author these docs.
