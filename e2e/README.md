# Godspeed Full-Day E2E

Automates a realistic delivery day (DEL↔HYD, HUB_RETURN) across all three repos. Design +
scenario matrix: [`docs/e2e/FULL-DAY-E2E.md`](../docs/e2e/FULL-DAY-E2E.md).

**Model:** Playwright drives the web consoles (business/hub/airline today); Maestro drives the DA +
shuttle mobile personas (pending); a thin **control plane** (this package) does the screenless steps
— seeding, state-gating on the customer track, flight time-travel, OTP peek. Every step that has a
screen is a real UI click; the personas that don't (DA/shuttle mobile, the clock) run on the control
plane until Maestro lands.

## Layout
```
harness/        reusable TS lib — ApiClient, ControlPlane, db (pg), factories, wait, constants, types
orchestrator/   seed.ts (bring-up), runDay.ts (control-plane-only day runner)
playwright/     pom/ (LoginPage, BusinessShipPage, HubConsole, AirlineConsole) + tests/
                  a1-spine.web.spec.ts  — full pipeline through the web consoles (hybrid)
                  consoles.smoke.spec.ts — 6-console load smoke
maestro/        DA + shuttle flows (pending)
```

## Prerequisites

### 1. Backend (local, JDK 21) against oneday-db-e2e + puffin
Boot it under `caffeinate` (a local backend + the remote Render DB + an idle Mac means macOS sleeps
mid-work and severs the connection). Three JVM flags relax clock-driven gates so the harness runs at
any hour — see `.env.backend` for the full rationale:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
set -a; source e2e/.env.backend; set +a
caffeinate -is "$JAVA_HOME/bin/java" \
  -Dgodspeed.pickup.operating-start-hour=0 -Dgodspeed.pickup.operating-end-hour=24 \
  -Dhub.hub-departure-lead-minutes=60 -Ddispatch.da.absent-threshold-minutes=1440 \
  -jar app/target/app-1.0.0-SNAPSHOT.jar
```
> Pass the `-D` flags as **literal separate tokens** (not `$E2E_JAVA_OPTS` — sourcing word-splits and
> merges them). Only **one** backend instance may run (competing RabbitMQ consumers break the flow):
> `for p in $(pgrep -f "bin/java.*app-1.0.0-SNAPSHOT.jar"); do kill -9 $p; done; pkill -9 -f "caffeinate -is"`.

### 2. Consoles (local `pnpm dev`, in the `oneday-web` repo)
Each app's `.env.local` sets `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`; business/customer also
carry `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY` (the ship form's map + Places autocomplete need it — verified
working on localhost). Ports mirror `harness/env.ts`; **airline collides with business on 3003 → run
it on 3005**:
```bash
cd ../oneday-web
pnpm --filter business dev      # :3003   (book — the hero UI)
pnpm --filter hub      dev      # :3001   (receive, flight-bag seal, delivery-bag seal)
pnpm --filter airline  dev -- -p 3005   # (AWB + GHA)  ← the `-p 3005` passthrough is blocked by its
#   hardcoded `-p 3003`; if it fails, run directly: (cd apps/airline && pnpm exec next dev -p 3005)
pnpm --filter customer dev      # :3000   (smoke only)
pnpm --filter station  dev      # :3002   (smoke only)
pnpm --filter admin    dev      # :3004   (smoke only)
```

### 3. Mobile (Maestro) — 🟢 GREEN
Android emulator (AVD `oneday_pixel`) + the driver app as a **dev client** on Metro started with
`EXPO_PUBLIC_API_URL=http://10.0.2.2:8080 EXPO_PUBLIC_ALLOW_EMULATOR=true EXPO_PUBLIC_MOCK_SCANNER=true`
(dev build → `__DEV__` true → emulator bypass + mock scanner live). Grant location perms once:
`adb shell pm grant com.oneday.driver android.permission.ACCESS_{FINE,BACKGROUND}_LOCATION`. Maestro
drives the DA (pickup + delivery) and shuttle (out + collect) legs; full recipe + gotchas in
[`maestro/README.md`](maestro/README.md).

## Run
```bash
npm install
npx playwright install chromium
npm run typecheck                 # harness + orchestrator + POMs/specs compile
npm run seed                      # control-plane bring-up → artifacts/personas.json
                                  #   (re-run any time; idempotent. Seeds BOTH shifts so a booking
                                  #    auto-assigns whatever the current IST hour.)

# Web pipeline (hybrid: real consoles + control plane), needs backend + business/hub/airline up:
npx playwright test -c playwright/playwright.config.ts -g "prepaid|COD"

npm run smoke                     # 6-console load smoke (needs all 6 consoles up, or override *_URL)
npm run day                       # control-plane-only day runner (no browser)

# Mobile hybrid day (DA + shuttle legs on the REAL Android app), needs backend + emulator + dev client:
npm run cleanup                   # shared-DB hygiene: cancel pre-pickup + drain delivery-stage parcels
npm run presence                  # re-establish DA presence for the current shift (lost on restart)
npm run mobile:day                # book → app pickup → cp hub/airline → app shuttle → app delivery → DROPPED

npm run report                    # open the Playwright HTML report
```
Evidence lands in `artifacts/playwright/` — `milestones/` (a screenshot per surface per scenario),
`report/` (HTML), `test-results/` (trace + video on failure).

## Scenarios (all green through the web consoles)
| ID | Lane | Pay | Web surfaces driven |
|----|------|-----|---------------------|
| A1 | DEL→HYD | prepaid | business book · hub receive+seal ×2 · airline AWB+GHA · hub delivery seal |
| A2 | HYD→DEL | prepaid | same, reverse lane (2nd business, CSHYDDEL flights) |
| B1 | DEL→HYD | COD | same + COD toggle at booking, cash collected at drop |

Each parcel traverses all 14 HUB_RETURN states to DROPPED; the DA-pickup, DA-delivery, shuttle, and
flight steps run on the control plane (Maestro will take the two DA/shuttle mobile ones).

## Gotchas (hard-won — keep)
- **DA `city_id`** registers as the grid.cities KEY form (`delhi`/`hyderabad`), not IATA — else the
  roster/shift-load never find the DA. **Shuttle agents keep IATA** (`DEL`/`HYD`).
- **Grid replan** needs explicit `{date:today, shift, daIds}` (empty body → tomorrow + empty plan).
- **Seed provisions BOTH shifts** (SHIFT_1 06–14, SHIFT_2 14–22 IST); dispatch resolves the shift
  from the current time and only assigns to an on-shift, present DA.
- **Clock gates** (all relaxed by the `-D` flags above): off-hours pickup hold; the 5h hub-departure
  lead that makes the last DEL↔HYD flight (22:00) unbookable after ~17:00; and the 15-min GPS
  absent-sweep that silently drops DA presence mid-session.
- **Flight re-use:** `departAndLand` pushes a flight's departure into the past to land it, so the
  spec calls `resetLaneFlightsToFuture` before each booking — else a later booking on that lane finds
  no future flight and won't assign.
- **Hub picker** (`od_hub_selected`) is per-origin localStorage; switch hubs only while on the hub
  origin (the POM lands there first). **Shuttle out-to-airport** waits for the AWB to be booked first
  (racing the async BAG_SEALED processing transiently poisons its transaction).
- **Mantine**: no `data-testid`s in the app — POMs use roles/labels/text; toggle a Switch by clicking
  `.mantine-Switch-track`; login fills retry against Next.js hydration resetting an early fill.
