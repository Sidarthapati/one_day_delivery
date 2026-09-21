# Maestro driver-app flows (mobile personas)

🟢 **GREEN end-to-end** — `orchestrator/mobileDay.ts` drives a DEL→HYD parcel to DROPPED with the DA
(pickup + delivery) and shuttle (out + collect) legs on the **real Android driver app**, the rest of
the pipeline on the control plane. `1/1 reached DROPPED through the real driver app`.

Maestro drives the two mobile personas of the full-day E2E that have no web console: the **DA**
(first-mile pickup + last-mile delivery) and the **shuttle agent** (hub ↔ airport). Same backend as
the Playwright web surface — the emulator hits it at `10.0.2.2:8080` (= host `localhost:8080`). These
flows are the mobile half of the hybrid; the web half is in `../playwright/`.

## Flows
| Flow | Persona | Drives | Key env |
|------|---------|--------|---------|
| `subflows/login.yaml` | any | clearState + sign in, leave `/login` | `EMAIL`, `PASSWORD` |
| `da-pickup.yaml` | DA (origin) | open stop → en route → arrived → scan → sender OTP → hand off at hub | `PARCEL_REF`, `OTP` |
| `da-delivery.yaml` | DA (dest) | collect from hub → en route → arrived → scan → recipient OTP → confirm (+ COD cash) | `PARCEL_REF`, `OTP`, `COD_AMOUNT?` |
| `shuttle-out.yaml` | shuttle (origin) | add sealed bag to trip → out to airport | `FLIGHT_NO` |
| `shuttle-collect.yaml` | shuttle (dest) | collected from airport (landed flight) | `FLIGHT_NO` |
| `driver-smoke.yaml` | DA | boot + login + route renders | — |

Selection is by `testID` (added to the flow-critical controls — see the driver-app instrumentation),
falling back to visible text for alerts/section headers. `task-open-${PARCEL_REF}` targets the exact
parcel row; `open-next-stop` opens the recommended stop hero.

## Prerequisites
1. **Android emulator running** (AVD `oneday_pixel`) with the driver app installed as a **dev
   client**, built with the E2E flags inlined:
   ```bash
   cd ../../../oneday-driver-app     # the oneday-driver-app repo
   EXPO_PUBLIC_API_URL=http://10.0.2.2:8080 \
   EXPO_PUBLIC_ALLOW_EMULATOR=true \
   EXPO_PUBLIC_MOCK_SCANNER=true \
     npx expo run:android            # dev build → __DEV__ true → emulator bypass + mock scanner live
   ```
   - `EXPO_PUBLIC_ALLOW_EMULATOR=true` — bypasses the device-integrity block (dev builds only).
   - `EXPO_PUBLIC_MOCK_SCANNER=true` — replaces the camera with a typed-ref panel so a scan can be
     injected (`mock-scan-input` + `mock-scan-confirm`). Both are `__DEV__`-gated → stripped from prod.
2. **Backend + a booked parcel gated to the right state** (the orchestrator does this): a pickup flow
   needs the parcel `PICKUP_ASSIGNED` to that DA; a delivery flow needs `HUB_DELIVERY_ASSIGNED`. The
   `OTP` values are the real codes peeked from the control plane.

## Run the full mobile day (the orchestrator)
```bash
# emulator up + dev client on Metro (below), backend up, broker not backlogged
npm run cleanup      # cancel pre-pickup + drain delivery-stage cruft (shared DB hygiene)
npm run presence     # re-establish grid + roster + DA presence for the CURRENT shift (lost on restart)
npm run mobile:day   # book → pickup(app) → hub/seal(cp) → shuttle-out(app) → airline/land(cp)
                     #       → shuttle-collect(app) → dest hub(cp) → hub-collect(cp) → delivery(app) → DROPPED
```
`mobileDay.ts` re-establishes presence itself; `cleanup`/`presence` are also standalone helpers.

## Run one flow (standalone)
```bash
export PATH="$HOME/.maestro/bin:$PATH"
maestro test da-pickup.yaml \
  --env EMAIL=da.del.shift_1.0@e2e.local --env PASSWORD=DaTest@2026 \
  --env PARCEL_REF=1DD-XXXXXX --env OTP=123456
```
The orchestrator shells these out with the live ref/OTP and bag/AWB **ids** per scenario (see
`../harness/maestro.ts`). Not yet a blocking CI gate — the mobile leg runs as a separate emulator job.

## Gotchas (hard-won — keep)
- **Dev client, not a release build.** The emulator device-integrity bypass needs `__DEV__`, so E2E
  runs a **dev client** served by Metro (`EXPO_PUBLIC_MOCK_SCANNER` + `ALLOW_EMULATOR` inline at bundle
  time — start Metro with them). `clearState` drops a dev build into the Expo **dev launcher**, so the
  login subflow instead **`openLink`s** the client onto Metro (`exp+onedaydelivery://…?url=…8081`),
  taps "Continue"/the scrim to dismiss the dev menu, then **signs out in-app** (Profile → `logout`) to
  switch persona. No `clearState` anywhere.
- **Grant location perms once** so the "Keep location running" prompt never blocks the work screen:
  `adb shell pm grant com.oneday.driver android.permission.ACCESS_{FINE,BACKGROUND}_LOCATION`.
- **`--env` vs flow `env:`** — a flow-level `env:` default SHADOWS `--env` in this Maestro (2.10). So
  the flows carry **no** `env:` defaults; the login subflow gets EMAIL/PASSWORD via `runFlow: {env:}`.
- **Key shuttle cards by id, not flight_no** — two bags can ride one flight, so the collide-free
  testIDs are `shuttle-add-<bagId>` / `shuttle-collect-<awbId>`; the flow `scrollUntilVisible`s them.
- **Delivery OTP is minted at hub-collect**, not at HUB_DELIVERY_ASSIGNED. Maestro can't read an OTP
  mid-flow, so the orchestrator does the hub-collect on the control plane first (mints + peeks the OTP),
  and the app resumes at the en-route phase (`delivery-receive` is `optional`).
- **Presence is in-memory** — re-run `npm run presence` after every backend restart, and it resolves
  the shift from the current IST hour (watch the SHIFT_2→SHIFT_1 flip at midnight).
- **Broker hygiene** — a long session on the free CloudAMQP (puffin) tier backs queues up and starves
  the async OTP/registry consumer (peek 404s). Purge via the mgmt API if `peek` stalls (queues visible
  at `https://<host>/api/queues/<vhost>`).
