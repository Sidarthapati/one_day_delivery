# Godspeed Full-Day E2E

Automates a realistic delivery day (DEL↔HYD, HUB_RETURN) across all three repos. Design +
scenario matrix: [`docs/e2e/FULL-DAY-E2E.md`](../docs/e2e/FULL-DAY-E2E.md). How it's built:
[`~/.claude/plans/…full-day-e2e…`](../docs/e2e/FULL-DAY-E2E.md) (plan) — summarised here.

**Model:** Playwright drives the 6 web consoles; Maestro drives the DA + shuttle mobile personas
on an Android emulator; a thin **control plane** (this package) does only the screenless steps
(seeding, state-gating, flight time-travel, OTP peek). Business actions are all real UI clicks/taps.

## Layout
```
harness/        reusable TS lib — ApiClient, ControlPlane, db (pg), factories, wait, constants, types
orchestrator/   seed.ts (bring-up), runDay.ts (the day; WIP)
playwright/     web-console POMs + specs (extends the existing consoles smoke)
maestro/        DA + shuttle flows
```

## Prerequisites
- **Backend** running locally (JDK 21, no active profile so `!prod` dev beans are on) against the
  dedicated **`oneday-db-e2e`** Postgres + a **`consolidator`** DB inside it + the **CloudAMQP
  `puffin`** broker. RabbitMQ + consolidator are mandatory for the airline leg.
- **Web** consoles via `pnpm dev` (Node 20, pnpm 9.15.9), `NEXT_PUBLIC_API_BASE_URL` → the backend,
  `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY` set (business ship form needs it). Airline on a non-3003 port.
- **Mobile**: Android emulator + a **development** dev-client APK (`expo run:android`) with
  `EXPO_PUBLIC_API_URL=http://10.0.2.2:8080 EXPO_PUBLIC_ALLOW_EMULATOR=true EXPO_PUBLIC_MOCK_SCANNER=true`.
- Node 20 + `npm install` here; Maestro CLI on PATH.

## Run
```bash
cp .env.example .env      # fill BACKEND_URL, E2E_DATABASE_URL, console URLs
npm install
npm run typecheck         # harness + orchestrator compile
npm run seed              # control-plane bring-up → artifacts/personas.json
npm run smoke             # existing 6-console load smoke (deployed or local via *_URL env)
npm run day               # full day (WIP)
```

## Status
- **Done + VALIDATED LIVE (Phase 0, 2026-09-19):** full stack up against oneday-db-e2e + puffin +
  the `consolidator_mock` schema. Control-plane path proven end-to-end: admin auth → HUB_RETURN both
  cities → 16 DAs + 2 hub ops + station + gha + 2 shuttles registered → grid replan+approve (today +
  shift + explicit daIds → 3466 hex rows/city, 100% coverage) → shift-load → DA check-in `PRESENT`.
  Backend `!prod` force-poll endpoint added. Typechecks clean.
- **🔴 Run the backend under `caffeinate -is`** — a local backend + the remote Render DB + an idle
  Mac means macOS sleeps mid-migration and severs the connection (the ~130-migration from-scratch
  boot takes minutes). Boot: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; set -a; source
  e2e/.env.backend; set +a; caffeinate -is "$JAVA_HOME/bin/java" -jar app/target/app-*.jar`.
- **Two seed invariants (baked into the harness):** DAs register with the grid.cities KEY form
  (`delhi`/`hyderabad`, not IATA `DEL`) or the roster/shift-load never find them; grid replan needs
  explicit `{date:today, shift, daIds}` (empty body → tomorrow + empty plan). Shuttle agents keep IATA.
- **Pending live backend:** clean seed re-run (needs a fresh DB or an idempotent seed — registration
  isn't idempotent yet); `ControlPlane.provisionB2bAccount` (onboarding→approve→credit) still a stub.
- **Next:** web `data-testid`s + Playwright POMs; driver-app mock-scanner + `testID`s + fixed Maestro
  flows (Maestro CLI installed); `runDay.ts` (A1 spine); then Tiers A/B → C → D → CI.
