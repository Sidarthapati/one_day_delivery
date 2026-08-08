# Phase 2 physical field test — execution runbook

> Companion to `PHASE-2-PHYSICAL-TEST-PLAN.md` (the original plan). This is the **verified, ready-to-run**
> runbook: what was audited GREEN in code, the one code gap that was closed, the operational enablement, and
> the two-person Hyderabad runbook for **HUB_RETURN (AM)** and **VAN_MEETING (PM)**.

## Context

Phase 2 = the **entire physical delivery chain except the flight leg (M9)**, driven by real people on real
devices against **Render staging (`main`)**. The finish line is **origin-hub flight-bag assignment** (via the
`hub` `StubFlightAssignmentPort`). Chain to prove:

```
book intercity (HYD→DEL) → print Code128 label → attach to parcel → DA sees PICKUP task
 → DA en-route (GPS) → arrive → camera-scan label → sender OTP → PICKED_UP
 → [2a HUB_RETURN] DA carries parcel to hub          → hub scan-in → FLIGHT BAG  ← finish
 → [2b VAN_MEETING] DA→van at meeting pt → van→hub    → hub scan-in → FLIGHT BAG  ← finish
```

---

## Readiness audit (verified in code — mostly GREEN)

| Piece | State | Evidence |
|---|---|---|
| B2C mock-pay / **B2B wallet** booking | ✅ | `B2cShipmentController` / `B2bShipmentController`; B2B now emits CREATED |
| Label = **shipment ref** as Code128 | ✅ | `apps/business/.../label/page.tsx` → `code128Svg(label.barcode_value)`; `barcode_value = getShipmentRef()` |
| Barcode consistent end-to-end | ✅ | label=ref; DA `isParcelBarcode()` accepts `1DD-HYD-…`; hub `receive(ref)` looks up by ref — same string throughout |
| DA app full pickup loop | ✅ | `PickupDetailScreen.tsx`: en-route→camera scan→OTP→PICKED_UP→handoff |
| DA app auto-picks HUB_RETURN vs VAN_MEETING | ✅ | off `cron.van_id` (`WorkScreen` + `PickupDetailScreen`) |
| Van app (loop/stop-confirm/load-scan/return-scan/telemetry) | ✅ | `src/screens/van/*`, `vanConfirmStop`/`vanScan` in `api.ts` |
| Hub console receive (single + batch, Enter-submit) | ✅ | `apps/hub/.../receive/page.tsx` (on `main`) |
| **HUB_RETURN state chain** | ✅ | `PICKED_UP→RETURNED_TO_HUB` (`HUB_RETURN_HANDOFF_COMPLETED`) `→AT_ORIGIN_HUB` (`HUB_ORIGIN_IN` seam) `→ORIGIN_HUB_PROCESSING` (hub receive). `ArrivalMode.fromState(AT_ORIGIN_HUB)=VAN` → OUTBOUND → bag. **No code needed.** |
| Login with temp password | ✅ | app ignores `must_change_password` → temp password logs straight in |
| Live tracking | ✅ | customer `track/[ref]` 12s poll + DA GPS 30s → live dot + milestones |
| HYD priced + hub coords | ✅ | `V2_4` seeds `('HYD','DEL',162)` + HYD B2B card `…d004`; routing hub from `city_logistics_node` |
| Flight-bag assignment (finish line) | ✅ | `SortServiceImpl.resolveOutbound` → `StubFlightAssignmentPort` |

---

## Code work (DONE this session)

### Dev pickup-OTP peek — the one real gap  *(backend `orders`, `!prod`)*
OTP is generated on `PICKUP_ASSIGNED` but stored **BCrypt-hashed** (cleartext never persisted) and SMS is a log
sink, so the tester couldn't read the code the DA must enter. Fix:
- `DevOtpRegistry` (`@Profile("!prod")`, in-memory `shipmentId→cleartext`) — `orders/.../service/DevOtpRegistry.java`.
- `PickupOtpServiceImpl.generate()`/`resend()` push cleartext via `ObjectProvider<DevOtpRegistry>` (prod path unchanged).
- `DevOtpController` (`@Profile("!prod")`) `GET /internal/dev/shipments/{ref}/pickup-otp` → `{ shipmentRef, otp }` (404 until pickup assigned).
- Test: `PickupOtpServiceImplTest` asserts the registry echoes the cleartext.

### Hub receive autofocus — scan-gun nicety  *(oneday-web `apps/hub`)*
Single-scan `TextInput` now `autoFocus` + clears & refocuses after each submit, so a **USB/Bluetooth HID
scan-gun** types the ref + Enter straight in with no click. No other hub changes — the gun is pure HID.

---

## Operational enablement (no code; do TODAY, city = **hyderabad** `6ba7b811-9dad-11d1-80b4-00c04fd430c8`)

Base: `https://one-day-delivery.onrender.com`, ADMIN JWT.
1. **Accounts** — admin `/das` (`POST /das`): register Agniva as `DELIVERY_ASSOCIATE` (city `hyderabad`, SHIFT_1,
   contract covering today) → capture `daId` (=JWT sub) + temp pw. For 2b register a `VAN_DRIVER` (Sid's 2nd login).
2. **Roster/plan** — admin `/das` "Generate & approve today's plan" for **hyderabad + SHIFT_1**
   (`POST /api/grid/{cityCode}/replan` + `POST /api/proposals/{id}/approve`).
3. **Hub node** — ensure a `city_logistics_node` HUB row for Hyderabad at the chosen hub (the house). Check
   `GET /routing/nodes/{cityId}`; seed if missing.
4. **Meeting-mode gate** — `PUT /routing/fleet/{cityId}` `meetingMode=HUB_RETURN` for the morning. (Default is
   VAN_MEETING.) Afternoon: flip to `VAN_MEETING` + van count ≥1.
5. **VAN_MEETING seed (PM)** — `POST /routing/plans/{cityId}/replan` then `POST /routing/plans/{planId}/approve`
   → `da_cron_schedule` with `van_id`. Confirm `GET /routing/cron/da/{daId}` shows `van_id` + meeting_times.
6. **Deploy** — confirm staging = `main` + correct DB; `eas build -p android --profile preview` → install on
   two phones (Agniva=DA, Sid=van). App defaults to staging.
7. **Hardware** — buy one cheap USB/BT **HID barcode scanner** (₹1k–2.5k) for the hub laptop. Smoke test: scan
   into Notepad → text + newline. DA/van phones use the camera (no gun).

---

## Physical setup (Hyderabad, 2 people, 1 car)
- **Hub = the house.** Sid books + runs the hub console + scan-gun on a laptop here.
- **Pickup point ~3–5 km away.** Before each run Sid prints the label, tapes it to a small box, and it is
  pre-placed at the pickup point. The "sender" need not be present — the OTP is read from the dev peek.
- **Sid (A):** books → prints/attaches label → hub operator → reads OTP peek → watches tracking; PM also van driver.
- **Agniva (B):** the DA on his phone — pickup, camera-scan, OTP, then carry to hub (2a) or hand to van (2b).

---

## Runbook — Phase 2a: HUB_RETURN (morning)

| # | Who | Action | Physical event → state / signal |
|---|---|---|---|
| 1 | Agniva | Open DA app → GPS auto-starts | OFFLINE→IDLE; `POST /dispatch/da/{daId}/gps` (confirm 1 ping) |
| 2 | Sid | Book **intercity HYD→DEL** (business `ship`, wallet), pickup pin at the pickup point | `BOOKED`; `ShipmentCreated` |
| 3 | — | M5 auto-assigns | `PICKUP_ASSIGNED`; PICKUP task in DA app; OTP minted |
| 4 | Sid | Open/print label (already on the parcel) | Code128 = the ref |
| 5 | Agniva | Tap **En route** | task IN_PROGRESS; GPS trail |
| 6 | Agniva | Drive to pickup, **Mark arrived**, **camera-scan** the label | scan recorded |
| 7 | Sid | `GET /internal/dev/shipments/{ref}/pickup-otp` → read to Agniva | — |
| 8 | Agniva | Enter OTP → **Confirm pickup** | `verify-otp` → `PICKED_UP` + `PICKUP_COMPLETED` |
| 9 | Agniva | **Hand off at hub** (drive parcel to the house) | `hub-handoff` → `RETURNED_TO_HUB` → `AT_ORIGIN_HUB` |
| 10 | Sid | Hub console **receive** → **scan-gun** the label | `ORIGIN_HUB_PROCESSING` → `resolveOutbound` → **flight bag** (`bags` page) **← finish** |
| 11 | Sid | Watch `track/[ref]` throughout | live dot follows Agniva; milestones advance in order |

## Runbook — Phase 2b: VAN_MEETING (afternoon)
Flip gate to VAN_MEETING + seed van/cron; Sid logs the 2nd phone in as VAN_DRIVER.
- Steps 1–8 as above. At **step 9** the DA app shows **"Hand to the cron van"** (`cron.van_id` set): Agniva does
  **van-handoff** at the meeting point → `VAN_HANDOFF_COMPLETED` → `HANDED_TO_PICKUP_VAN`.
- Sid (van app): **stops/confirm** (COLLECT / `DA_TO_VAN`) → drive to hub → **return-scan** (`VAN_UNLOAD`) → `AT_ORIGIN_HUB`.
- Sid → hub console **receive** (scan-gun) → flight bag. Same finish, through the van.
- Known limit (accept): continuous **van** GPS is deferred — van dot updates on stop events; DA heartbeat still flows.

---

## All physical events → state transitions
`BOOKED` → `PICKUP_ASSIGNED` → `PICKED_UP` →
 **2a:** `RETURNED_TO_HUB` → `AT_ORIGIN_HUB` → `ORIGIN_HUB_PROCESSING` (bag) ·
 **2b:** `HANDED_TO_PICKUP_VAN` → `AT_ORIGIN_HUB` → `ORIGIN_HUB_PROCESSING` (bag).
Optional failure paths: "Report a problem" → `PICKUP_FAILED` → "Try again this shift" (`reattempt`); customer
cancel → `CANCELLED`.

## Verification checklist (per run)
- [ ] DA IDLE + PICKUP task within seconds of booking (G1 closed).
- [ ] Camera scan accepts the label; **dev OTP peek returns a code**; `verify-otp` → `PICKED_UP`.
- [ ] `track/[ref]` live dot follows Agniva's real GPS; milestones advance in order.
- [ ] Hub scan-gun → success; parcel in the **correct dest-hub flight bag** with a stand.
- [ ] (2b) van `stops/confirm` reconciles; `return-scan` closes the manifest; hub receive still bags it.
- [ ] No `NO_DA_AVAILABLE` / `no cron slot` in staging logs.

## Out of scope for Phase 2
Flight + destination legs (M9 → Phase 3), real SMS/OTP, EWB generation, continuous van GPS, hardware scanners
on the DA/van phones (camera is used there).
