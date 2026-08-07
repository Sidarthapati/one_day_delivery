# Phase 2 Physical Test — First-mile → Hub → Flight-bag (no M9)

> Status: **plan, not yet implemented.** Target: green by **Aug 8, 2026**.
> Branch: `f-phase-2-testing`. Implementation starts the day after this doc lands.

## Context

Three-phase field-test program for the one-day delivery platform:
- **Phase 1** — GPS pings show correct DA/van coordinates. **Done.**
- **Phase 2** *(this doc)* — the entire physical chain **except the flight leg**, driven by **real
  actors on real devices**, against **Render staging on `main`**.
- **Phase 3** — full flow **with** the M9 flight module.

Target: Phase 2 green by **Aug 8** so a full week remains for Phase 3.

> **Update (2026-08-07):** `main` has advanced — the branch was merged up to date. **M9 (airline)
> now exists on `main`** (real `AirlineController`, AWB booking/tracking, a read-only
> freight-consolidator integration, `V9_*` migrations), plus hub/station console parity fixes and a
> new dispatch **deferred/station-console** manual-assignment path (`StationDispatchController`).
> Phase 2 still stops at the **origin-hub flight-bag assignment** (we don't exercise the flight/AWB
> leg here), but the "M9 is empty" framing below is historical — M9 is no longer a blank.
> **G1 was redesigned** from a throwaway dev endpoint into a real feature (admin DA registration +
> shift-aware roster) — see G1 below. The van-driver handoff scan was **verified already built** (not
> a gap) — see the "already complete" table.

**The chain we must prove (Phase-2 finish line = origin-hub flight-bag assignment):**
customer places an intercity order → label (Code128) → DA sees pickup task → DA travels, scans, verifies
sender OTP → **[Phase 2a] DA carries to hub (HUB_RETURN)** / **[Phase 2b] DA hands to van at meeting point →
van carries to hub** → hub scans parcel in → **parcel assigned to the correct flight bag** (via the M9 stub).
Live tracking updates throughout. The actual flight + destination legs are **out of scope for Phase 2**
(M9 module is empty; nothing to disable — it's the natural state).

**Decisions locked:**
- Sequencing: **HUB_RETURN spine first (2a), then add the van meeting-point leg (2b).**
- Hub step UI: use the **existing hub console on `oneday-web` branch `feat/hub_console`** (real:
  receive/bags/staging pages + hub endpoints in `packages/api`).
- Environment: **Render staging deploying `main`** (has full M4/M5/M6/M7); both phones point here.
- **Descoped for Phase 2:** EWB generation (passthrough string only; needs GST-portal creds — go-live
  bucket) and hardware scan-guns (driver app is camera-scan only — camera works).

Backend module map (all in one Spring Boot app): `orders`=M4, `grid`=M3, `dispatch`=M5, `routing`=M6,
`hub`=M7, `barcode`=M8, `airline`=M9 (empty).

---

## What is already complete (verified, file-level)

| Step | Status | Where |
|---|---|---|
| Order placement (B2C mock-pay / **B2B wallet** — easier lane) | ✅ | `orders` `B2cShipmentController` / `B2bShipmentController`; `MockPaymentController`/`MockWalletController` (`!prod`) |
| Label = shipment ref as **Code128** (business portal prints it) | ✅ | `MyShipmentsController` `GET /shipments/mine/{ref}/label`; web `apps/business/.../label/page.tsx` + `lib/barcode.ts` |
| Internal parcel barcode `1DD-{destHub}-{yyMMdd}-{seq}` | ✅ | `barcode` `POST /api/v1/scan/label`; stamped onto shipment by `ScanEventsConsumer` |
| M5 dispatch **logic** (assign least-loaded DA, cron feasibility) | ✅ (blocked by roster, below) | `dispatch` `DispatchServiceImpl.assignPickup`; DA API `DaDispatchController` `/dispatch/da/{daId}/*` |
| Pickup + OTP (**use the dispatch verify-otp**) | ✅ | `POST /dispatch/da/{daId}/tasks/{taskId}/verify-otp` → emits `PICKUP_COMPLETED` (the orders-side `/internal/.../pickup-otp/verify` does **not** — avoid) |
| DA GPS heartbeat (feeds live tracking) | ✅ | driver app `src/location.ts` → `POST /dispatch/da/{daId}/gps` (30s) |
| Hub receive → **flight-bag assignment without M9** | ✅ | `hub` `POST /hub/{hubId}/receive` → `SortServiceImpl.resolveOutbound` → `StubFlightAssignmentPort` (4 departures/day) |
| Hub console UI (receive, bags, staging) | ✅ (on branch) | `oneday-web` `feat/hub_console` `apps/hub/app/(console)/*` |
| Live tracking (12s poll, live dot + milestones) | ✅ | `orders` `GET /shipments/mine/{ref}/track`; web `apps/customer/.../track/[ref]/page.tsx` |
| Driver app: task list, camera scan, en-route/arrived, OTP, hub-handoff | ✅ | `oneday-driver-app` `src/screens/pickups/PickupDetailScreen.tsx`, `WorkScreen.tsx` |
| **Van-driver scan of the DA→van handoff** (records DA→van custody) — *verified 2026-08-07, not a gap* | ✅ | van side: `oneday-driver-app` `src/screens/van/VanStopConfirmScreen.tsx` → `vanConfirmStop` → `POST /routing/vans/{vanId}/stops/confirm` (`collect_scanned` + a `COLLECT` telemetry ping per parcel, reusing `BarcodeScanner`); DA side: `vanHandoff` in `PickupDetailScreen.tsx` |

---

## Gaps that block a real run — and the enablement work to close them

All small.

### G1 — DA roster is empty → **0 DAs ever assigned** (the root blocker) — **BUILT 2026-08-07**
Historically `grid` `NoOpDaRosterPort` returned empty → no proposal DAs → no `APPROVED
da_hex_assignment` → `ShiftLoadJob` registered 0 DAs → every pickup deferred `NO_DA_AVAILABLE`.

**Fix (shipped, not a dev shortcut): admin DA registration + a real shift-aware roster.** A DA is a
`DELIVERY_ASSOCIATE` user plus an HR profile carrying a **contract window** and a **shift**; the roster
for a shift on a day = every DA whose contract covers the date and who is on that shift. The territory
plan now runs **once per shift** (SHIFT_1 06–14, SHIFT_2 14–22 IST). What landed:

- **auth** — `Shift` enum (`common`), `da_profile` table (`app` `db/migration/auth/V1_14`), `DaProfile`
  entity/repo, `POST /das` (admin/station-manager registration, reusing `UserServiceImpl.register` +
  returning a one-time temp password), `GET /das`, `PUT /das/{id}`; the roster query
  `DaDirectoryPortAdapter` (implements the new `common` `DaDirectoryPort`: active DELIVERY_ASSOCIATE in
  the city, on the shift, contract not expired).
- **grid** — `DaRosterPort` gains `Shift`; real `DirectoryDaRosterPort` replaces the no-op; `shift` on
  `assignment_proposal` (`V3_10`) scopes approve-supersede so SHIFT_2's approval doesn't clobber
  SHIFT_1; `NightlyReplanJob` loops cities × shifts; `POST /api/grid/{cityCode}/replan` takes a `shift`.
- **dispatch (M5)** — `ShiftLoadJob` loads only the firing shift's roster and populates
  `da_status.shift_type`; `ShiftEndJob` ends **only** the shift that's ending (fixes the prior bug where
  the 13:45 SHIFT_2 load was wiped by the 14:05 SHIFT_1 teardown).

**Enablement for a test "today"** (production-grade, no `!prod` hack): the admin **registers the DA(s)**
in the admin console, then triggers **"generate & approve today's plan"** per city+shift — the real
`POST /api/grid/{cityCode}/replan` (with `shift` + the roster) followed by
`POST /api/proposals/{id}/approve`. The nightly cron does the same at 01:00 for the next day.
**City code must match** `users.city_id` (grid keys: `delhi`/`mumbai`/`bangalore`/`hyderabad`/`chennai`).
The DA's login JWT `sub` is the `daId` returned by `POST /das`.

### G2 — Pickup OTP is never delivered (SMS = log sink) → DA can't complete pickup
OTP is generated on `PICKUP_ASSIGNED` but `SmsSender` defaults to log; pickup `resend-otp` returns void
(doesn't echo the code). The sender (our tester) has no way to read it.

**Fix — a dev-only OTP peek**: `GET /internal/dev/shipments/{ref}/pickup-otp` (`!prod`) returning the current
cleartext OTP, so the customer-tester reads it to the DA-tester. (Alternative: read Render server logs — slower.)

### G3 — HUB_RETURN hub-handoff → hub receive state bridge (verify, likely fine)
In HUB_RETURN the DA uses `hub-handoff` (task COMPLETED, shipment → a "handed to hub" state); the hub console
then calls `/hub/{hubId}/receive`, whose `ArrivalMode` derives from shipment state. **Verify in dry-run** that
the post-hub-handoff state maps to an arrival mode `HubReceivingServiceImpl.receive` accepts (VAN/AT_ORIGIN_HUB
path). If it doesn't, add that state to the arrival-mode mapping — small.

### G4 — Same-city collapse
Booking DEL→DEL collapses the flight (`isSameCity`). **Book intercity (DEL→BOM)** so the parcel actually stops
at origin-hub flight-bag assignment (our finish line). Pure test-data discipline, no code.

### G5 — Deploy surfaces
- **Hub console** (`feat/hub_console`) must be deployed (Vercel) with `NEXT_PUBLIC_API_BASE_URL` → staging.
  Either deploy the branch directly or merge to `main` first.
- **Driver app**: build an **EAS preview/dev-client APK** (`eas build -p android --profile preview`), install on
  the DA phone; it defaults to staging (`src/config.ts`). No Expo Go (native modules).
- **Pre-flight**: confirm the currently-deployed staging build is from **`main`** (autoDeploy=false → last manual
  deploy), and confirm the **staging DB** is the intended one (Singapore-vs-Oregon ambiguity noted in project
  memory — resolve before seeding, since `prime-day` writes to whatever DB staging points at).

---

## Enablement work summary (before any physical run)

| # | Deliverable | Repo / path | Size |
|---|---|---|---|
| E1 | **DONE 2026-08-07** — admin DA registration + shift-aware roster (replaces the old prime-day dev endpoint): `POST /das`, `DaDirectoryPort`/`DirectoryDaRosterPort`, per-shift `NightlyReplanJob`, shift-scoped `ShiftLoadJob`/`ShiftEndJob`. Same-day: admin registers DAs → "generate & approve today's plan" (`/replan` + `/proposals/{id}/approve`). | backend `auth`/`grid`/`dispatch` + `oneday-web apps/admin` (E4a) | shipped |
| E2 | `GET /internal/dev/shipments/{ref}/pickup-otp` (`!prod`) — still needed (SMS is a log sink) | backend `orders` (reads `PickupOtp`) | ~1 hr |
| E3 | Verify/patch HUB_RETURN handoff→receive arrival-mode mapping (G3) | backend `hub` `HubReceivingServiceImpl` / `ArrivalMode` | verify; ≤half day if needed |
| E4 | Deploy hub console → staging; confirm base URL | `oneday-web` `feat/hub_console` → Vercel | ~1 hr |
| E4a | Admin "Delivery Associates" page (register form + list) + "generate & approve today's plan" action | `oneday-web` `apps/admin` + `packages/api` | ~half day |
| E5 | EAS preview APK + install on DA phone | `oneday-driver-app` | ~2 hr (build queue) |
| E6 | Pre-flight: confirm staging=`main`, correct DB; create DA (2a) + van (2b) accounts | ops | ~1 hr |

---

## The physical test runbook

**Roles (2 people):** *Tester A* = customer (books on web) + hub operator (hub console on laptop) + [2b]
van driver (second driver-app login). *Tester B* = DA (driver app on phone). Two people cover both phases.

### Phase 2a — HUB_RETURN spine (do this first)
Pre-flight: E1–E6 done; city set to **HUB_RETURN**; **DA registered** (admin console `POST /das`, SHIFT_1,
contract covering today, city = grid code) and **today's plan generated + approved** for that city+shift;
DA phone has the APK pointing at staging; the DA's login uses the email + temp password from registration
(clear `mustChangePassword` for the pilot DA if the app doesn't yet handle the forced change).

1. **DA on shift** — Tester B logs into driver app (the registered DA account) → app auto-starts GPS
   heartbeat (`/dispatch/da/{daId}/gps`) → DA flips OFFLINE→IDLE (assignable). Confirm one ping landed.
   *(If no task ever arrives: confirm the DA is in the approved plan for today's shift and that the current
   time is inside the shift window — SHIFT_1 06–14, SHIFT_2 14–22 IST.)*
2. **Book** — Tester A books an **intercity B2B** shipment (`apps/business` `ship`, wallet-funded — no gateway)
   DEL→BOM with a real pickup pin near the DA. Shipment → BOOKED, `ShipmentCreated` fires.
3. **Auto-assign** — M5 assigns the pickup to the DA; a **PICKUP task appears** in the driver app
   (`WorkScreen`). *(If it doesn't: re-run `prime-day`; confirm the DA is IDLE and serves the origin tile.)*
4. **Label** — Tester A prints/opens the Code128 label (`apps/business` `shipments/[ref]/label`).
5. **Pickup** — Tester B: En route → Arrived → **camera-scans** the label → Tester A reads the OTP from the
   **dev OTP peek** (E2) → Tester B enters it → `verify-otp` → `PICKED_UP` + `PICKUP_COMPLETED`.
6. **Carry to hub** — HUB_RETURN → Tester B uses **hub-handoff**
   (`/dispatch/da/{daId}/tasks/{taskId}/hub-handoff`) → task COMPLETED, shipment → handed-to-hub state.
7. **Hub receive** — Tester A (hub console `receive` page) scans the ref → `POST /hub/{hubId}/receive` →
   `resolveOutbound` → **flight bag assigned**. Verify on the `bags` page: parcel is in the correct dest-hub
   bag. **← Phase-2a finish line.**
8. **Tracking** — throughout, Tester A watches `apps/customer` `track/[ref]` (12s poll): live DA dot +
   milestones (Booked → Picked up → At hub). Confirm the dot tracks the DA's real position.

### Phase 2b — add the van meeting-point leg
Flip the city to **VAN_MEETING** and re-run `prime-day` with a `vanId` (seeds fleet + approved route plan →
`da_cron_schedule` with `van_id`). Create a `VAN_DRIVER` account for Tester A.

- Steps 1–5 as above. Then at step 6 the DA sees **van-handoff** instead of hub-handoff (because the cron now
  carries a `van_id`): Tester B does `van-handoff` at the meeting point.
- Tester A logs the driver app in as **van driver** (VAN_DRIVER) → van UI: `stops/confirm` at the meeting point
  (COLLECT / `DA_TO_VAN` scan) → drives to hub → `return-scan` (VAN_UNLOAD).
- Tester A switches to the hub console and does **hub receive** → flight bag. Same finish line, now through the van.
- Known limitation: continuous **van** GPS via telemetry `GPS` is deferred (van live dot updates on stop
  events, not every second); DA-side heartbeat still flows. Acceptable for Phase 2 — note it, don't fix now.

### Optionally exercise the destination legs (dry, no M9)
There is **no demo orchestrator**; each transition is a real actor call. To sanity-check the last-mile shape
without M9, hand-drive states via the hub console `break-bag` + delivery pages — but the **true** last-mile
test belongs to **Phase 3 with M9**. Recommendation: stop Phase 2 at the origin-hub flight-bag assignment and
invest the remaining runway in Phase 3.

---

## Verification checklist (per run)
- [ ] DA shows IDLE and a PICKUP task appears within seconds of booking (proves G1 closed).
- [ ] Camera scan accepts the label; OTP peek returns a code; `verify-otp` → `PICKED_UP`.
- [ ] `track/[ref]` live dot follows the DA's real GPS; milestones advance in order.
- [ ] Hub console `receive` returns success and the parcel lands in the **correct dest-hub flight bag** with a stand.
- [ ] (2b) Van `stops/confirm` reconciles the DA handoff; `return-scan` closes the manifest; hub receive still assigns the bag.
- [ ] No `NO_DA_AVAILABLE` / `has no cron slot` in staging logs during the run.

## Suggested timeline to Aug 8
- **Day 1–2:** E1 (prime-day) + E2 (OTP peek) + E3 verify; E6 pre-flight (staging=main, DB, DA account).
- **Day 2:** E4 (hub console deploy) + E5 (driver APK).
- **Day 3:** dry-run Phase 2a solo (one person drives all roles) to shake out state bridges.
- **Day 4:** real Phase 2a with both people.
- **Day 5–6:** Phase 2b (van leg) — prime-day with van, VAN_DRIVER account, real run.
- **Buffer:** Aug 7–8. → Phase 3 (M9) starts with a full week.
