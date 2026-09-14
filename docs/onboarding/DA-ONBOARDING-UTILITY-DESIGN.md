# DA Onboarding Utility — Design

> Status: **DESIGN / proposal.** No code yet. Source: Knostics KT session (`da_onboarding_knostics.mov`).
> Effort: **large** (of the three, this is the real build). Scope decision (confirmed): **full self-service** —
> invite link → multi-step candidate wizard + document upload + e-agreement + training + **mocked per-check
> background verification** (IDfy-style) + a pre-approval queue → convert an approved candidate into today's
> `DaProfile` + an employee-ID.

## 1. Why

Today a delivery associate is created by a single admin `POST /das` with optional Aadhaar/PAN **text** fields and
no verification. Amazon/Knostics run a real onboarding funnel: a candidate fills a multi-step form, uploads ID
documents, signs an agreement, watches training, passes a third-party **background verification (BGV)**, and only
then is provisioned and marked active. We want the same so DA onboarding is self-service, auditable, and gated on
verification — mirroring how Amazon does it (with the paid vendor **mocked** where we don't have a contract yet).

## 2. Reference flow (from the video)

### 2.1 Amazon side
- **DA detail / task checklist** (`.../delivery-associates/detail/…`) — `screens/amazon-da-detail-onboarding.jpg`:
  status chip `ONBOARDING` + Change Status; **"Onboarding — 4 of 19 Completed"**; task groups **Associate Settings**
  (delivery location + service type), **Driver's License** (enter DL details), **Agreement**, **Videos/Training**.
  Fields: Offering Type, Email, Mobile, DOB, **DA Contract Type = Independent Contractor**, Primary delivery
  station, Service types, Supervisor, Resend invitation.
- **People › Onboarding funnel** (`workforce?pageId=da_console_onboarding`) — `screens/amazon-onboarding-funnel.jpg`:
  counts On track / Behind / Attention-needed; **stages S1 Onboarding started → S2 BGC in progress → S3 BGC
  complete → Account Provisioning**; per-DA row Name+ID / Position / ID expiration / Contact / **Progress %** /
  Status (action item, e.g. "DA: Accept email invitation"); conversion-rate & avg-completion-time per stage.

### 2.2 Background verification — IDfy (`portal.dc.idfy.com`) — the vendor we mock
- `screens/idfy-bgv-dashboard.jpg`: company-scoped dashboard; nav Dashboard / **Initiate Background Verification** /
  Progress Report / Inactive Requests; KPIs Total / In Progress / **Insufficient** / Completed → **Green / Amber /
  Red** counts.
- `screens/idfy-candidate-profile.jpg`: a candidate has **per-check cards**, each with a **Green/Amber/Red status
  and a timeline**: **PAN** (checked via NSDL), **Driving License**, **Address** (permanent), **Database** (Indian &
  International DB / criminal), **eFIR**, **Police Verification**. Overall "Status: Green — Completed before time".
  Lifecycle per check: *Task Created → Verification not Initiated → In-progress → Verifying Information → Green
  Closed*. Side panel: Request Verification, Upload LOA, Generate Realtime Report, Documents Pre/Post-Placement.

### 2.3 Knostics side — "Partner Details" wizard (our closest blueprint)
`screens/knostics-partner-details-documents.jpg` (`/admin/user/edit_user_step_four_*`, breadcrumb Home › Partners
List › Add Partner). Multi-step tabs: **Personal Info | Additional Info | Preferred Location | Bank Details | My
Documents | Office Use 1 | Office Use 2 | User Logs**. The **My Documents** tab = *Documents To Verify*: Aadhaar No.
+ Aadhaar Image 1 & 2 (uploaded → per-doc view/delete/download + **"Success"** verified badge), Voter No. + image,
PAN No. + image. Approval queue (under Manage Operators): **Pre-Approval Join Request → Pre-Approval Approver**.
Once approved, the DA gets a **Knostics-ID** (and Amazon employee-ID) — a second internal id used for billing.

**Distilled model:** `candidate applies (multi-step + docs) → BGV runs per-check → manager approves → DA is
provisioned with an employee/Knostics-ID`.

## 3. What we have today

DA creation is thin, in **auth (M1)**:
- `POST /das` (`auth/.../api/DaController.java`, ADMIN/STATION_MANAGER) — one-shot. `RegisterDaRequest`
  (name, email, phone, cityId, shift, contract dates, aadhaar, pan, **panDocUrl = text pointer only, no binary
  upload**, password) → `DaRegistrationServiceImpl.register` → user (`DELIVERY_ASSOCIATE`, `mustChangePassword`)
  + `DaProfile` / `da_profile` (`V1_14`). **No** status/verification columns, doc upload, KYC, BGV, approval,
  agreement, training, or employee-ID.
- "Station" is a **city scope**, not an entity; territory is per-day H3 hex (`da_hex_assignment`, grid M3), set by
  the replan, **not** at registration.

**The reusable scaffolding (this is the key to a sane build):** a real KYC + multi-step approval workflow already
exists — for **B2B businesses**:
- `auth/.../api/OnboardingController.java`: `POST /auth/request-onboarding`, admin queue
  `GET /onboarding-requests`, `POST /onboarding-requests/{id}/approve|reject`.
- `auth/.../service/impl/OnboardingServiceImpl.java`: `PENDING → KYC verdicts → auto-approve (clean) or ADMIN
  review → activate(...)` (creates the user, audits the role grant, provisions downstream).
- Entity `OnboardingRequest` → `onboarding_requests` (`V1_11_1` +…); config `OnboardingProperties` (auto-approve
  thresholds).
- **Vendor seam** = `common/.../port/KycPort.java` + single impl `SandboxKycAdapter` — deterministic **mock** when
  `kyc.live=false`, real REST when live (env `KYC_LIVE/KYC_API_KEY`). This is precisely the pattern to clone for a
  **`BgvPort`** + mock IDfy adapter.
- Document upload plumbing: `common/.../port/ObjectStoragePort.java` (presign) + the parcel-measurement
  presign→upload→submit precedent (`orders/.../api/ParcelMeasurementController.java`).

So we **clone the B2B onboarding pattern for DAs**, add a document-upload step and a per-check BGV port, and
converge on the existing `DaProfile` at the end.

## 4. Gap (what to build)

1. A **candidate** concept distinct from an active DA (a person mid-onboarding), with a **state machine**.
2. **Self-service invite link** + a multi-step candidate wizard (Personal / Location / Bank / **Documents**).
3. **Document upload + verify** (Aadhaar/PAN/DL, +Voter) — binary upload, not the current text pointer.
4. **e-Agreement** acknowledgement + **training video** watched-acknowledgement.
5. A **`BgvPort`** with a mock IDfy adapter running **per-check** verification (PAN, DL, Address, Criminal/DB,
   Police) with GREEN/AMBER/RED + "insufficient", plus polling/lifecycle.
6. A **pre-approval queue** (review → approve/reject), then **conversion** to `DaProfile` + **employee/Knostics-ID**.
7. An onboarding **funnel view** (stage counts + per-candidate progress %), mirroring Amazon's People › Onboarding.

## 5. Proposed design

### 5.1 State machine (on the candidate)
```
DRAFT ─submit→ SUBMITTED ─docs+identity ok→ BGV_INITIATED ─poll→ BGV_IN_PROGRESS
   ├─ BGV_CLEAR (all checks green) ──→ PENDING_APPROVAL ─approve→ APPROVED ─provision→ ONBOARDED
   ├─ BGV_INSUFFICIENT (needs more docs) ──→ back to candidate (re-upload)
   └─ BGV_FAILED (red) ──→ PENDING_APPROVAL (manual override allowed) / REJECTED
```
Reuse the project's state-machine style (mirrors M4 `ShipmentStateMachine` / registry approach) or the simpler
status-transition of B2B onboarding — pick to match `OnboardingServiceImpl` for consistency.

### 5.2 Data (new tables — new Flyway in auth, next free ≈ `V1_18`)
- `da_onboarding_candidate` — `id, invite_token, status, first/last name, email, phone, dob, city_id, shift,
  preferred_location, contract_type, bank_* , employee_id (assigned on approve), knostics_id, created_at, …`.
- `da_onboarding_document` — `id, candidate_id, doc_type (AADHAAR|PAN|DL|VOTER), object_key, verify_status
  (PENDING|SUCCESS|FAILED), number`. (Binary in R2; key here.)
- `da_bgv_check` — `id, candidate_id, check_type (PAN|DL|ADDRESS|CRIMINAL|POLICE), vendor_ref, state
  (NOT_INITIATED|IN_PROGRESS|GREEN|AMBER|RED|INSUFFICIENT), updated_at`. Append-only status history is fine.
- `da_onboarding_acknowledgement` — agreement + per-training-video acks (`candidate_id, kind, ack_at`).

At `APPROVED → ONBOARDED`, write the existing `da_profile` (and user) from the candidate — **no change to the DA
runtime model**; onboarding is a front-stage that terminates in today's `DaProfile`.

### 5.3 The `BgvPort` (mock the vendor — mirror `KycPort`/`SandboxKycAdapter`)
```java
// common/.../port/BgvPort.java
BgvInitiateResult initiate(BgvRequest req);        // req: candidate + checks[] + doc refs
List<BgvCheckResult> poll(String vendorRef);       // per-check {type, state, message}
```
- `common/.../port/dto/bgv/*` DTOs (BgvRequest, BgvCheckResult, BgvState…).
- `auth/.../service/impl/MockBgvAdapter.java` — deterministic verdicts when `bgv.live=false` (default): e.g. clean
  by default, configurable seeds to demo AMBER/RED/insufficient. Config `BgvProperties` (prefix `bgv`, env
  `BGV_LIVE/BGV_API_KEY/BGV_API_SECRET`) — **never commit keys** (same rule as Razorpay/KYC).
- Real `IdfyBgvAdapter` later swaps in via `@Primary` when the IDfy contract exists (initiate = IDfy "Initiate
  Background Verification"; poll = candidate-profile per-check status). Keep the identity legs (PAN/Aadhaar) on the
  existing `KycPort`/Sandbox if we prefer.

### 5.4 Endpoints
Candidate (public, token-gated by `invite_token` — no login yet):
```
POST /api/v1/da-onboarding/invites                 (ADMIN/STATION_MANAGER)   → create candidate + invite link
GET  /api/v1/da-onboarding/{token}                 (public)                  → current step + status
PUT  /api/v1/da-onboarding/{token}/personal|location|bank                    → save wizard steps
POST /api/v1/da-onboarding/{token}/documents/upload-url                      → presign (ObjectStoragePort)
POST /api/v1/da-onboarding/{token}/documents                                 → submit keys + numbers
POST /api/v1/da-onboarding/{token}/agreement/ack
POST /api/v1/da-onboarding/{token}/training/ack
POST /api/v1/da-onboarding/{token}/submit                                    → SUBMITTED → triggers BGV
```
Admin / station-manager queue:
```
GET  /api/v1/da-onboarding/queue?status&cityId      → funnel + list (mirrors Amazon People › Onboarding)
GET  /api/v1/da-onboarding/{id}                      → full candidate + doc + BGV cards
POST /api/v1/da-onboarding/{id}/approve              → APPROVED → provision DaProfile + employeeId
POST /api/v1/da-onboarding/{id}/reject?reason
POST /api/v1/da-onboarding/{id}/bgv/retry            (insufficient → re-run)
```
Gating consistent with `OnboardingController` (ADMIN always; STATION_MANAGER own city).

### 5.5 BGV orchestration
On `submit`: verify docs present → `BgvPort.initiate` → store `da_bgv_check` rows `IN_PROGRESS`. A
`BgvPollJob` (@Scheduled) polls `BgvPort.poll` for in-progress candidates, updates check states, and advances the
candidate to `BGV_CLEAR` / `BGV_INSUFFICIENT` / `BGV_FAILED`. (In mock mode the adapter returns terminal states
quickly.) This mirrors how other async vendor flows are polled.

### 5.6 UI (`oneday-web` + candidate mini-site)
- **Candidate wizard** (token URL): Personal → Location → Bank → Documents (camera/upload, per-doc "Success") →
  Agreement → Training → Submit — the Knostics "Partner Details" tabs.
- **Station/admin console:** onboarding funnel (stage counts, progress %), candidate detail with the **BGV cards**
  (Green/Amber/Red per check, like IDfy), and Approve/Reject.

## 6. Build slices (sized; ship incrementally)
1. **S1 — candidate + wizard core:** tables, state machine, invite link, Personal/Location/Bank steps, admin queue
   list/detail, approve→provision `DaProfile`+employeeId (no BGV/docs yet — proves the funnel end-to-end).
2. **S2 — documents:** presign upload + submit + verify status (Aadhaar/PAN/DL), reusing `ObjectStoragePort` and
   the parcel-measurement pattern.
3. **S3 — BGV:** `BgvPort` + `MockBgvAdapter` + `BgvProperties`, per-check rows, `BgvPollJob`, BGV gating of the
   approval step, BGV cards in the console.
4. **S4 — agreement + training:** acknowledgements + gating.
5. **S5 — funnel polish:** stage counts, conversion-rate/avg-time, "attention needed" flags (Amazon parity).
6. **S6 (later):** real `IdfyBgvAdapter` behind `@Primary` when the vendor contract lands.

## 7. Open questions
- **Q-O1 (blocks a clean model):** introduce a real **station/hub entity** a DA is assigned to, or keep "station" =
  city + per-day hex? Amazon/Knostics assign a **primary station**; today we only have city. (Same gap flagged in
  the station-health doc — worth deciding once for both.)
- **Q-O2:** employee-ID / Knostics-ID scheme — format, uniqueness, who issues it (us vs mirrored from Amazon).
- **Q-O3:** which BGV checks are in-scope for v1 (PAN + DL + Criminal/DB feel minimal; Address + Police heavier)?
- **Q-O4:** self-service auth — token-gated public wizard (proposed) vs require phone-OTP login first (we have M1
  phone-OTP already).
- **Q-O5:** training content source (video hosting) and whether "watched" needs real proof or a simple ack.
- **Q-O6:** does BGV `RED` hard-block, or allow a manager manual override (Amazon allows manual intervention)?

## 8. Reference screens
- `screens/amazon-onboarding-funnel.jpg`, `screens/amazon-da-detail-onboarding.jpg`
- `screens/idfy-bgv-dashboard.jpg`, `screens/idfy-candidate-profile.jpg`
- `screens/knostics-partner-details-documents.jpg`
- `screens/contact-sheet-32-48min-healthscore-partner-details.png`
