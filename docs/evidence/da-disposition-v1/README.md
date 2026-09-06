# DA Disposition v1 — Live E2E Evidence (2026-09-07)

Full stack booted against a **fresh throwaway DB** `oneday_disp_e2e` (all migrations applied in-order,
incl. **`V5_21 create da disposition` = ok**, confirming the migration is clean on a fresh DB — the local
V5_20 collision does not exist here). Backend jar on :8094, station web on :3002, Android emulator
`oneday_pixel` running the `oneday-driver-app` dev client (branch `feat/da-disposition-v1` on all repos).
Seeded one Delhi SHIFT_1 DA (Ravi Kumar) with an approved grid assignment + a 13:00 cron/hub-return
meeting, loaded into dispatch memory by `ShiftLoadJob` (`1 DAs, 1 with cron, 1 hexes`).

## Backend API e2e (live, via real JWTs)

| Step | Result |
|---|---|
| `GET …/disposition/slots` | `[06:00–12:30]` and `[13:00–14:00]` IST — the **12:30–13:00 pre-cron protected window is excluded** ✅ |
| `POST …/request` BREAK (before ping) | 409 "Cannot request a break while OFFLINE" (state guard) ✅ |
| `POST …/gps` | 204 → DA resumes IDLE ✅ |
| `POST …/request` BREAK now (00:44 IST, pre-shift) | 409 "That break does not fit an allowed slot (it would risk your cron / hub-return)" (**slot guard, live**) ✅ |
| `POST …/request` AUXILIARY (company work, 45m) | PENDING (no allowance charge, DA keeps working) ✅ |
| `GET /dispatch/disposition/pending?cityId=delhi` | manager sees the request with DA name ✅ |
| `POST /dispatch/disposition/{id}/approve` | ACTIVE + DA status → **ON_BREAK** (territory held) ✅ |
| force overstay + wait for `DispositionMonitorJob` (~60s) | ACTIVE → **OVERSTAYED**, escalation_level 5, WARN "escalated to station manager"; manager view shows `minutes_overdue` ✅ |
| `POST …/end` ("I'm back") | COMPLETED, **duration refined 45→14 min** (early-return refund), DA → IDLE ✅ |
| `POST …/request` DAY_OFF → `POST …/{id}/reject` | PENDING → REJECTED ✅ |

Final `da_disposition` ledger for the DA: `AUXILIARY COMPANY_WORK -> COMPLETED (dur=14, esc=5)`,
`DAY_OFF OTHER -> REJECTED`.

> Note on BREAK auto-approve success: the wall-clock during the run was 00:42 IST — the overnight gap
> between SHIFT_1 (06–14) and SHIFT_2 (14–22) — so a real *break-now* correctly refused (slot guard shown
> above). BREAK auto-approve → ON_BREAK is covered by the 10 unit tests (`DaDispositionServiceImplTest`);
> the driver-app screenshots show the full BREAK UI (reason picker + Start break).

## Screenshots

- **disp-web-01-pending.png** — station console → Dispositions tab: "Pending requests (1)" Ravi Kumar ·
  Auxiliary · Company work · "Help load the hub van" with **Approve / Reject**.
- **disp-web-02-overstay.png** — after approving in the UI: "Overstayed breaks (1)" Ravi Kumar · 36 min
  overdue · **Mark absent & reassign** (reuses the existing absence flow) + the approve toast.
- **disp-driver-01-overstay.png** — driver app: AWAY · red **"Break exceeded — please return"** · "Due
  back by 12:42 AM" · **I'm back** (live overstay from the backend).
- **disp-driver-02-takebreak.png** — driver app: "TAKE A BREAK · 60 min of break time left today" ·
  **Break / Auxiliary / Day off**.
- **disp-driver-03-breakpicker.png** — reason picker **Lunch / Rest / EV charging** + **30 / 60 min**
  (splittable allowance) + **Start break**.
