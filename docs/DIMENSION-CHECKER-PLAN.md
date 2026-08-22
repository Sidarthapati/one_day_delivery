# First-Mile Parcel Dimension Checker — Design & Plan

> Status: **DRAFT / for discussion.** Branch `f-dimension-checker`. Not yet implemented.

## Context

**Problem.** Merchants under-declare parcel L×W×H at booking. The true (larger) dimensions are only discovered at the hub, triggering a chargeback to the merchant's wallet and downstream disputes. We want to catch this at the **first mile** — when the delivery agent (DA) picks the parcel up — using the DA's phone camera.

**Two hard constraints:** (1) phone-camera only, on a heterogeneous, mostly-Android, mostly-cheap fleet; (2) error margin must be low enough to be trusted for evidence.

## Decisions locked (initial discussion)

- **Role of the scan = evidence + early-catch**, NOT the billing source of truth. The hub measurement stays authoritative. The pickup scan produces an *estimate + photographic evidence* and flags gross mismatches on the spot, so disputes become resolvable and merchants can't claim "I declared correctly."
- **A printed reference marker is acceptable** for DAs to carry/place. This is the single biggest accuracy lever — it injects a *known* real-world scale, so we no longer estimate absolute scale from an unknown camera.
- **Open-source / self-built** engine for v1: **OpenCV + ArUco**. (Monocular AI depth like Depth Anything V2 was ruled out — ~0.45 m real-world error, not billing-grade. Commercial SDKs like Scandit are the fallback only if the open-source benchmark fails.)
- **Hybrid processing:** on-device ArUco for live capture guidance/preview; the **authoritative measurement is recomputed server-side** so results are consistent across all devices and tunable in one place.
- **Moderate discrepancy tolerance:** flag when measured exceeds declared by **>10% of volume OR any single side by >2 cm**. Tunable via config.

## Tooling survey (why ArUco + OpenCV)

| Approach | Verdict | Notes |
|----------|---------|-------|
| **OpenCV + ArUco** (`opencv-contrib`) | ✅ **Primary** | Known-size marker injects real-world scale → lowest, most *controllable* error on any camera. Free. |
| **ARCore Depth API** ([arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab), [ArCoreMeasurement](https://github.com/Kashif-E/ArCoreMeasurement)) | ➕ Assist | Depth-from-motion, no special hardware, ~88% Android coverage; few-cm error that degrades on cheap phones. |
| **Monocular AI depth** (Depth Anything V2, ZoeDepth) | ❌ Ruled out | ~0.45 m real-world error; no reliable absolute scale from a single RGB frame. |
| **Commercial SDK** (Scandit, MobileDemand) | 🅱️ Fallback | Contractually-warranted accuracy, paid. Only if the open-source benchmark fails. |

## Current-state findings (all green-field)

- **DA app** = React Native + Expo (`~57`), EAS/dev-client builds, JWT bearer auth, `expo-camera ~57.0.3` already present (barcode-only). `src/api.ts` is JSON-only (no upload plumbing); wire format is snake_case. Pickup flow is a single-screen state machine `DETAIL→ENROUTE→ARRIVED→OTP→PICKED_UP→DONE` — captures **no** dimensions today. (Repo: `oneday-driver-app`, sibling of the backend.)
- **Backend** = no object storage (no S3/R2/disk), no photo/POD capture anywhere, no PDF generation. Only multipart prior art is an **in-memory** Excel upload (`BulkUploadController`). Shipment dimension columns are `updatable=false` (frozen at booking). Wallet exists but has **no chargeback transaction type**; no reweigh/discrepancy pipeline exists in code — today's chargeback is a manual/operational process.

## Approach: ArUco marker + hybrid CV, evidence-grade

### End-to-end flow
1. DA reaches `ARRIVED`/`PICKED_UP`, taps **Scan dimensions**.
2. DA lays the printed **ArUco marker mat** on a flat surface, places the parcel on it, follows on-screen guidance. On-device ArUco gives a live "markers detected / rough size" overlay (Android; iOS = capture-only in v1).
3. App captures the guided photo(s), uploads them directly to object storage via a **presigned URL**, then posts the object keys + on-device preliminary dims to the backend.
4. Backend calls the **CV service** (Python/OpenCV) which recomputes authoritative L×W×H + confidence from the stored photos. This is the number of record.
5. Backend writes an append-only `pickup_measurement` row (declared vs measured + evidence keys + method + confidence), computes the discrepancy against the frozen booking dims, and emits an event.
6. App shows the DA the measured dims + a clear "matches / over-declared by X" verdict to confirm before completing pickup.

### Marker fixture (accuracy foundation)
- Printed **ArUco board/mat** — multiple markers of exactly-known size (e.g. an A3 grid). Multi-marker → robust plane pose even when the parcel occludes some markers. The mat is the ground plane and the scale reference.
- **Height** is the trickiest CV step. v1 default: **two guided captures** — top-down (marker coplanar with the parcel base → L×W) and an oblique/side shot (→ H). A single-oblique-shot method (`solvePnP` ground-plane pose, back-project base + top corners) is a stretch goal validated by the spike.
- Cheap-phone intrinsics variance is mitigated because marker and parcel base are coplanar (planar homography is robust to unknown focal length) — a core reason the marker approach beats markerless here.

### Component 1 — DA app (React Native / Expo)
- New `DimensionScanner` component modeled on `src/components/BarcodeScanner.tsx` (same `expo-camera` permission/`CameraView` pattern).
- New on-device Android **Expo native module** (Kotlin + OpenCV Android SDK + `aruco`) exposing `detectMarkers/roughDims` for the live overlay. Android-first; iOS = capture-and-upload only in v1.
- New "Scan dimensions" step wired into `src/screens/pickups/PickupDetailScreen.tsx` (available in `ARRIVED`/`PICKED_UP`).
- `src/api.ts`: add `getEvidenceUploadUrl(...)` (presigned) + `submitDimensions(daId, taskId, body, token)` following the existing `taskAction`/`dropCompleted` house style (positional `token`, snake_case). Add `expo-file-system` for the direct-to-storage upload.

### Component 2 — CV service (new Python/OpenCV sidecar)
- Small **FastAPI** service, `opencv-contrib-python` (ArUco). `POST /measure` takes storage object keys, returns `{length_cm, width_cm, height_cm, confidence, method}`. Deployed alongside the backend (Render/Hetzner).
- Rationale over JavaCV-in-JVM: cleaner CV iteration/tuning in Python; avoids packaging native OpenCV into the Spring JAR. (This would be the platform's first non-JVM service — open decision.)

### Component 3 — Backend (orders + dispatch)
- **Object storage:** introduce an S3-compatible store (recommend **Cloudflare R2** — S3 API, no egress; swappable). New storage config + a **presigned-PUT endpoint** so photos upload directly (not proxied through the JVM, unlike `BulkUploadController`). Render-disk is the fallback.
- **New endpoint:** `POST /dispatch/da/{daId}/tasks/{taskId}/dimensions` on `dispatch/.../api/DaDispatchController.java`, authorized via the existing `Authz.requireDaSelf(principal, daId)` — mirrors the existing `drop-completed`/`failed` endpoints. New request/response DTOs alongside `TaskFailedRequest`/`DropCompletedRequest`.
- **New append-only entity** `PickupMeasurement` (orders `domain/`) + Flyway migration (next `V4_x`): shipment ref, declared L/W/H snapshot, measured L/W/H, method (`ARUCO`/`MANUAL`), confidence, evidence object keys, `over_declared` flag, actor DA id, timestamp. Do **not** mutate `Shipment` (dims are `updatable=false` by design — fits the append-only audit ethos).
- **Discrepancy service:** compare measured vs frozen booking dims using the moderate tolerance (`>10%` volume OR `>2 cm` any side), configurable. Sets the flag; does **not** touch the wallet in v1.
- **Event:** emit a new `DimensionDiscrepancyFlagged` (common `events/`) on `oneday.shipments.events` via the existing `ShipmentEventProducer` AFTER_COMMIT pattern — the hook a future chargeback/ops module consumes.

### Out of scope for v1
Automated wallet chargeback. This is evidence-grade only; the hub stays the billing source of truth. The emitted event is the integration hook for a later chargeback/ops flow.

## Phasing (de-risk accuracy before full build)

- **Phase 0 — Accuracy spike (gate).** Build the marker mat + a throwaway Python ArUco script. Measure ~30 real parcels on the target cheap Android device(s) vs caliper ground truth. **Acceptance gate:** median error within the moderate tolerance. If it fails → revisit fixture geometry / more views, or escalate to the paid-SDK fallback. *Do this before the app/native-module work.*
- **Phase 1 — v1 build.** CV service → backend storage + endpoint + entity + discrepancy + event → DA-app capture/upload + on-device Android preview → DA confirmation UX.

## Open decisions to resolve

- Object storage provider: **Cloudflare R2** (recommended) vs Render disk vs other.
- Standing up the **first Python service** in an otherwise all-JVM platform (vs JavaCV in the monolith).
- iOS scope for v1 (recommend Android-first live preview; iOS capture-and-upload only).
- Exact marker geometry: two-photo (robust, simple) vs single-oblique-shot mat (one capture, more CV) — decided by the Phase 0 spike.

## Verification

- **Accuracy benchmark (Phase 0 gate):** scripted CV-output vs caliper ground truth on ~30 parcels/target devices; median error within the moderate tolerance.
- **Backend:** integration test for `POST .../dimensions` (auth via `requireDaSelf`, presigned-upload happy path, `PickupMeasurement` persisted append-only, discrepancy flag at boundary cases, event emitted).
- **CV service:** unit tests on a fixture image set with known dimensions.
- **DA app:** Maestro E2E extending the pickup flow through the scan step; manual run on a real Android device end-to-end.
- **Demo:** deliberately under-declare a parcel at booking, run the DA pickup scan, confirm the app flags the over-declaration and the `pickup_measurement` row + evidence photos land in storage.
