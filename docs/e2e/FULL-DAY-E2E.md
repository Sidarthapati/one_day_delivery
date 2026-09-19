# Full-Day End-to-End Test — Plan & Scenario Coverage

**Status:** DRAFT for review (2026-09-17). No code written yet.
**Goal:** Automate a realistic *full delivery day* across two cities (DEL ↔ HYD, HUB_RETURN
mode, no vans/routing) and prove that every persona's UI and the whole parcel lifecycle
work end-to-end — not one parcel, but a set of parcels covering **100% of the scenarios**
the platform supports. Runnable repeatedly, with rich reports, ready for CI.

Three repos are in scope:
- `one_day_delivery` — Java 21 / Spring Boot backend (system under test)
- `oneday-web` — 6 Next.js consoles (customer, business, hub, airline, station, admin)
- `oneday-driver-app` — one Expo SDK 57 app; DA **and** shuttle are personas inside it

---

## 1. The central reality check (why the architecture is what it is)

1. **The pipeline is real and complete.** `CLAUDE.md`'s status table is stale — every
   module is built and wired (M9 airline, M12 shuttle, M5 dispatch, M7 hub, M10 SLA,
   M11 exceptions). The intercity HUB_RETURN state chain exists exactly as expected:
   `BOOKED → PICKUP_ASSIGNED → PICKED_UP → RETURNED_TO_HUB → AT_ORIGIN_HUB →
   ORIGIN_HUB_PROCESSING → IN_TAKEOFF_BAG → DISPATCHED_TO_AIRPORT → AT_AIRPORT →
   DEPARTED → LANDED → DISPATCHED_TO_HUB → AT_DEST_HUB → DEST_HUB_PROCESSING →
   HUB_DELIVERY_ASSIGNED → COLLECTED_FROM_HUB → DROPPED`.

2. **The DA + shuttle app cannot be driven by Playwright.** It is Expo SDK 57 React
   Native with native-only deps (camera, secure-store, background GPS, Play Integrity,
   `jail-monkey`) and **no working web build** (`react-native-web`/`react-dom` are not
   installed). Playwright is a browser tool. → **Mobile UI is driven by Maestro** on an
   Android emulator (the app already has `.maestro/` flows and an `EXPO_PUBLIC_ALLOW_EMULATOR`
   escape hatch; `expo run:android` is wired).

3. **There is no day-simulator / time-travel.** No `DemoFullDayService`, no "run the day"
   endpoint. The day advances by real UI/API actions plus a tiny **control plane** (see §4).

4. **Zero `data-testid`, sparse ARIA** in all 6 web apps and near-zero `testID` in the
   mobile app. Robust selectors require adding scoped test hooks (approved: "both, scoped").

### Verdict
A full-day E2E is **feasible** and covers the entire pipeline through real UI, using:
- **Playwright (browser)** → the 6 web consoles (business, hub, airline, station, admin, customer)
- **Maestro (Android emulator)** → the DA and shuttle personas of the mobile app
- **A thin HTTP/SQL "control plane"** → *only* the handful of steps that have no screen
  (flight time-travel, OTP peek, shift-boundary trigger). Business actions are never raw REST.

---

## 2. Guiding principle — UI-first, minimal control plane

Coverage over volume. Every business action is performed on the surface a real operator
would use. Raw REST/SQL is confined to an explicitly enumerated **control plane** (§4) that
represents "things a human/clock/airline does that we can't physically reproduce in a test."
Everything else is a click or a tap.

Parcel counts are **whatever each scenario needs** — not a fixed 100. A scenario that needs
2 parcels uses 2. Volume/distribution is its own scenario (S-OPS-2), not a tax on every test.

---

## 3. Environment

**Recommendation (decided):**
- **Database:** a **new database on the existing Render Postgres instance** (not the shared
  staging DB). Rationale: a full simulated day seeds rosters/agents/businesses, books many
  parcels, runs `UPDATE flight_instance` time-travel, and triggers shift jobs — blast radius
  is too large for shared staging, and Flyway/seed collisions are likely. A sibling DB on the
  same instance costs nothing extra, reuses infra, and isolates completely. (A throwaway local
  Postgres is the fallback for fully-offline / CI-native runs.)
- **RabbitMQ:** a **new instance / vhost** (you provision) — event isolation is mandatory so
  100+ parcels' events don't collide with anything else.
- **Backend:** run locally (or a dedicated ephemeral deploy) with `!prod` profile, pointed at
  the new DB + new broker. `GridSeeder` seeds the 5 city H3 grids on boot; mock payment/wallet
  and dev-OTP controllers are available under `!prod`.
- **Web:** all 6 apps via `pnpm dev`, `NEXT_PUBLIC_API_BASE_URL` → local backend.
  ⚠️ **Resolve the `business`/`airline` port collision** (both default to 3003) — run airline
  on an overridden port.
- **Mobile:** Android emulator, `EXPO_PUBLIC_API_URL=http://10.0.2.2:8080`,
  `EXPO_PUBLIC_ALLOW_EMULATOR=true`, plus the new **mock-scanner flag** (§5).

---

## 4. The Control Plane (the *only* allowed non-UI operations)

These have no screen; they stand in for a human, a clock, or an external party. Everything
else is UI.

| Op | Mechanism | Stands in for |
|----|-----------|---------------|
| **Flight depart/land** | `UPDATE flight_instance SET departure/arrival = now()+…`, picked up by `FlightStatusPollJob.pollProgress` | The aircraft flying. No UI exists. |
| **Force flight poll** *(new test hook)* | `@Profile("!prod")` endpoint to run `pollProgress()` on demand + lowered `airline.status-poll-delay-ms` in test config | Avoids waiting the 5-min poll cycle |
| **Shift boundary** | `POST /dispatch/admin/shift-load?date=&shift=SHIFT_2` | The 13:45 cron. No operator UI button. |
| **OTP peek** | `GET /internal/dev/shipments/{ref}/pickup-otp` / `delivery-otp` (ADMIN, `!prod`) | The sender/receiver reading their SMS. Value is injected into Maestro. |
| **State gating (read-only)** | `GET /api/v1/shipments/mine/{ref}/track` | Orchestrator waiting for a state before the next tool acts |
| **Seeding** | admin API + SQL seed | DAs, shuttle/hub/station/GHA users, 2 B2B accounts, flight schedule |

No business state transition (book, pickup, scan, seal, dispatch, collect, deliver) is done by
raw REST — those are all UI (Playwright or Maestro).

---

## 5. Instrumentation to add (scoped, approved)

**Web (`oneday-web`) — `data-testid` on only the elements the tests assert on:**
- Login: email/password inputs + submit (all 6 apps)
- Business `ship` / `bulk`: quote, book, order-status rows
- Hub console: hub picker, receive/scan input, bag list rows, open/seal/dispatch buttons,
  delivery-bag rows
- Airline console: schedule rows, AWB stamp, GHA-accepted/handed-over/loaded buttons
- Station console: dispatch board DA-queue rows, attendance muster, absence, shift-close, SLA rows
- Admin: DA roster table, shuttle-agent register form

**Mobile (`oneday-driver-app`):**
- **Mock-scanner flag** (new): an `EXPO_PUBLIC_MOCK_SCANNER` path so `BarcodeScanner` accepts
  an injected/typed parcel ID instead of the `CameraView` (today the "Enter sender OTP" button
  is hard-gated on a real camera scan — `PickupDetailScreen.tsx:254`). Small, `!prod`-style flag.
- `testID` on the load-bearing controls in the DA + shuttle flows Maestro drives.

**Backend (`one_day_delivery`):**
- `@Profile("!prod")` **force flight-poll** endpoint + test config lowering the poll interval.
- (Everything else — OTP peek, shift-load, mock payment/wallet — already exists.)

---

## 6. Scenario Matrix (the coverage contract)

Tiers: **Core** (must, the spine of the day) → **Variants** (payment/handoff shapes) →
**Operational** (shift/roster/close) → **Exceptions & Features** (the built-out edge cases).
"Surfaces" lists who drives each: **BIZ**=business console, **HUB**=hub console,
**AIR**=airline console, **STN**=station console, **ADM**=admin, **DA**=driver-app (Maestro),
**SH**=shuttle (Maestro), **CTL**=control plane (§4).

### Tier A — Core pipeline
| ID | Scenario | Dir | Surfaces | Notable states |
|----|----------|-----|----------|----------------|
| A1 | B2B intercity, DA pickup + DA delivery, credit | DEL→HYD | BIZ→DA→HUB→AIR→CTL→SH→HUB→DA | full chain to DROPPED |
| A2 | Same as A1, reverse direction, 2nd business | HYD→DEL | same | full chain |
| A3 | Same-city HUB_RETURN (no flight) | DEL→DEL | BIZ→DA→HUB→DA | `…→HUB_DELIVERY_ASSIGNED→COLLECTED_FROM_HUB→DROPPED` |
| A4 | Multi-parcel B2B order (N shipments grouped) | DEL→HYD | BIZ (order + add-shipment)→… | order groups N shipments |

### Tier B — Payment & handoff variants
| ID | Scenario | Surfaces | Notable |
|----|----------|----------|---------|
| B1 | COD parcel — collect on delivery + settlement | BIZ→…→DA(COD)→STN/ADM (COD recon) | `codAmountToCollectPaise` set; DA cash balance |
| B2 | Self-drop pickup (`SELF_DROP`) — customer drops at hub | BIZ→HUB(receive)→… | skips DA pickup / M5 assign |
| B3 | Self-collect delivery (`SELF_COLLECT`) — receiver collects at dest hub | …→HUB→(no delivery DA) | ends at hub-collect by receiver |
| B4 | Wallet-funded vs credit-funded booking | BIZ (wallet top-up via mock) | `fundingSource=WALLET` |

### Tier C — Operational (roster / shift / close)
| ID | Scenario | Surfaces | Notable |
|----|----------|----------|---------|
| C1 | DA attendance / check-in before shift | DA (check-in), STN (muster) | attendance visible in station |
| C2 | **Shift change** — book in SHIFT_1, cross to SHIFT_2, unfinished tasks roll | CTL(shift-load)→STN→DA(shift 2) | `ShiftEndJob` deferral; SHIFT_2 DAs pick up |
| C3 | **DA-queue distribution** — spread pickups across hexes → many DAs | BIZ (varied pins)→STN (board shows spread) | proves fair distribution |
| C4 | Shift close / van return (asset shift-close SC1) | DA (return-van)→STN/ADM | end-of-shift reconciliation |

### Tier D — Exceptions & built-out features (M11 + shipped features)
| ID | Scenario | Surfaces | Notable |
|----|----------|----------|---------|
| D1 | Pickup failure + reattempt | DA(`/failed`,`/reattempt`)→STN | exception queue |
| D2 | Delivery failure → RTO (return to origin) | DA→STN/ADM (RTO worklist) | `<ref>_R` reuse, RTO_COMPLETED |
| D3 | Cancellation before pickup + refund | BIZ (cancel)→STN | `ShipmentCancelledEvent`, refund |
| D4 | B2B order repair (add/remove parcel pre-pickup) | BIZ (edit order)→DA weight-cap | capacity 409 path |
| D5 | Flight reassignment / disruption → bag re-point | CTL(reassign)→HUB(BAG_RESCHEDULED)→AIR | M9→M7 seam |
| D6 | SLA breach — let a leg go late (AMBER/RED) | CTL(delay)→STN (SLA console) | M10 escalation |
| D7 | Midday DA absence → reassignment (custody collect) | STN(absence)→DA(custody-collect) | flood-fill hex split |
| D8 | Hub overload alert | HUB (load) | `HUB_OVERLOAD_ALERT` |

> Tier A+B+C are the "must" spine of the day. Tier D scenarios are each independently valuable
> and get built in priority order; some (D5–D8) are advanced and may land in a later phase.
> **Final scenario list to lock is the purpose of your review of this doc.**

---

## 7. Orchestration & sequencing

The day is inherently **sequential per parcel** (can't pick up before it's booked and lands in a
queue) but **parallel across parcels**. A single **orchestrator** (Node/TS) drives it:

```
orchestrator (Node)
  ├─ Playwright projects  → web consoles (browser contexts, seeded sessions)
  ├─ Maestro runner       → DA + shuttle flows on the emulator (parameterized via --env)
  └─ control-plane client → SQL + the §4 endpoints (time-travel, shift-load, OTP peek, gating)
```

**Cross-tool handoff pattern:** after a UI action, the orchestrator *polls* the track endpoint
(`GET /shipments/mine/{ref}/track`) until the parcel reaches the state the next surface expects,
then invokes that surface. Maestro flows are parameterized (parcel ref, injected OTP, DA login)
and launched by the orchestrator at the right moment. OTP values come from the ADMIN peek and are
passed into Maestro as env vars.

**Session handling (speed + robustness):** seed each web app's `od_*_token`/`od_*_session`
`localStorage` keys directly from a logged-in fixture instead of re-typing login every test — with
one real UI login test per app to prove the login screen.

---

## 8. Best practices (to avoid the "no structure / repeat-yourself" trap)

- **Page Object Model** per console; **flow objects** for multi-step journeys.
- **One typed API/control-plane client** reused everywhere (mirror `packages/api` contracts; DRY).
- **Fixtures** for auth, seeded data, and per-scenario parcel factories.
- **Deterministic seed** (fixed DAs, hexes, flight numbers) so runs are reproducible.
- **Playwright HTML report + trace-on-failure + screenshots** at every milestone (your debugging
  surface). Maestro produces its own run artifacts; collect both into one report bundle.
- **Serial within a scenario, parallel across independent scenarios** where the shared backend
  state allows; tag scenarios (`@core`, `@exceptions`, `@mobile`) for selective runs.
- **No hard sleeps** — always gate on observed state via the track/read endpoints.

---

## 9. CI integration

- **Web + control-plane portion** (Playwright + REST/SQL) is fully CI-friendly: spin backend +
  DB + broker (dockerized or the dedicated Render DB), run headless Chromium, publish the HTML
  report. This is the primary regression gate.
- **Mobile (Maestro) portion** needs an emulator — runs as a **separate CI job** (the driver repo
  already has `.eas/workflows/e2e.yml` running Maestro on an EAS-hosted device). It coordinates
  through the shared backend state, not in-process with Playwright.
- Wire as `workflow_dispatch` first (like the existing `playwright.yml`), promote to a PR/nightly
  gate once stable.

---

## 10. Phasing (proposed build order)

1. **Foundations:** dedicated DB + broker, seed scripts (DAs, agents, 2 businesses, DEL↔HYD
   flight schedule), control-plane client, test-id + mock-scanner instrumentation, Playwright/
   Maestro scaffolding on top of the existing `e2e/` dirs.
2. **Tier A spine (A1)** end-to-end through every surface — the proof the rig works.
3. **Tier A2–A4 + Tier B** (directions, same-city, multi-parcel, COD, self-drop/collect).
4. **Tier C** (attendance, shift change, distribution, shift close).
5. **Tier D** exceptions/features in priority order.
6. **CI wiring** + report bundling.

---

## 11. Open questions for your review

1. **Lock the scenario list** — is Tier A+B+C the v1 target, with Tier D following? Any scenario
   missing (e.g. Google/OTP customer login, tracking page, B2C alongside B2B)?
2. **Flight realism** — seed real IndiGo/AI/Vistara DEL↔HYD flight numbers (I'll fetch a plausible
   static schedule and roll it forward), or is the synthetic consolidator seed fine?
3. **Emulator in CI** — OK to keep the Maestro/mobile leg as a separate (possibly EAS-device) job,
   or must web + mobile run in one pipeline?
4. **Backend hosting for the run** — local backend, or a dedicated ephemeral Render deploy against
   the new DB/broker?

---

## Appendix — key endpoints & files (verified during exploration)

- **Auth:** `POST /auth/login` → `{token, role, cityId}`; ADMIN `admin@oneday.in` is superuser.
- **B2B booking:** `POST /api/v1/b2b/shipments` (`B2bShipmentController`), multi-parcel via
  `POST /api/v1/b2b/orders/{orderRef}/shipments` (`B2bOrderController`).
- **DA actions:** `DaDispatchController` `/dispatch/da/{daId}/tasks/{taskId}/{en-route|arrived|
  hub-handoff|hub-collect|drop-collected|drop-completed|failed|reattempt|custody-collected}`.
- **OTP:** verify `POST /internal/v1/shipments/{ref}/pickup-otp|delivery-otp/verify`; peek
  `GET /internal/dev/shipments/{ref}/…` (ADMIN, `!prod`).
- **Hub:** `HubReceivingController` receive; `HubBagController` bags/seal/dispatch;
  `HubInboundController` delivery-bags/seal.
- **Airline:** `AirlineController` schedule/AWB/`gha-accepted`/`dest-shuttle-in`/`handed-over`/
  `loaded`; `FlightStatusPollJob.pollProgress` for depart/land.
- **Shuttle:** `ShuttleController` `/shuttle/{id}/queue|out-to-airport|awbs/{awbId}/collect-from-airport`.
- **Meeting mode:** `PUT /routing/fleet/{cityId}` → set `HUB_RETURN`.
- **Shift:** `POST /dispatch/admin/shift-load?shift=SHIFT_2`; `ShiftLoadJob`/`ShiftEndJob`.
- **Existing test scaffolds:** `one_day_delivery/e2e/playwright/`, `one_day_delivery/e2e/maestro/`,
  `oneday-driver-app/.maestro/`.
- **Mobile scan gate:** `oneday-driver-app/src/components/BarcodeScanner.tsx` (camera-only),
  `src/screens/pickups/PickupDetailScreen.tsx:254` (OTP button gated on scan).
