# Gap: console authorization + city scoping

> Status: **known gap, deferred.** Found 2026-08-09 during Phase-2 field-test setup. Safe for the
> controlled field test (only trusted ADMIN accounts exist); **must be hardened before real users.**

## The gap in one line

Most console/back-office endpoints are **authenticated-only** — no role check and no city scoping.
Any logged-in user (even a customer) can hit hub / routing / grid / airline endpoints for **any city**.

## How it works today

- **HTTP layer** (`auth/.../security/SecurityConfig.java`): a short public allowlist
  (`/auth/login`, `/auth/register`, `/auth/health`, `GET /api/v1/track/**`, static), then
  **`anyRequest().authenticated()`**. **No role or city rules at this layer.**
- **Method layer**: role/city checks are ad-hoc per controller. Only **two** places actually enforce:
  - `auth` `DaController` (`/das`) → `@PreAuthorize("hasAnyRole('ADMIN','STATION_MANAGER')")`.
  - `dispatch` `StationDispatchController` (`/dispatch/tiles/**`) → `Authz.requireRole(STATION_MANAGER)`
    **+ city scope** `scopeCityId = isAdmin ? null : managerCity(principal)` (from `User.cityId`).
    **This is the correct pattern to copy.**
- **Frontend "expected roles"** are a **non-blocking warning banner** only (except the admin app, which
  hard-redirects non-ADMIN). Not security.

| Console | Frontend expected roles | Backend role check | City-scoped? |
|---|---|---|---|
| Admin | `ADMIN` (hard block) | ✅ (`/das`) | n/a (all cities) |
| Station | `STATION_MANAGER`,`SUPERVISOR`,`ADMIN` (warn) | ✅ | ✅ **yes** |
| **Hub** | `HUB_OPERATOR`,`ADMIN` (warn) | ❌ none | ❌ no |
| **Airline** | `AIRLINE_GHA`,`ADMIN` (warn) | ❌ none | ❌ no |
| **Routing** (`/routing/**`) | — | ❌ none | ❌ no |
| **Grid** (`/api/grid`, `/api/proposals`) | — | ❌ none | ❌ no |

## Worst cases

- Any authenticated user can `POST /hub/{hubId}/receive` (or seal bags) for **any hub** — `hubId` is a URL
  path param the client picks from a dropdown, never validated against the operator.
- Any authenticated user can flip `PUT /routing/fleet/{cityId}` (meeting mode, van count) or approve a grid
  proposal (`POST /api/proposals/{id}/approve`) for any city.

## Fix (when we come back to it)

Apply the station pattern to hub / routing / grid / airline:
1. Give each module an `Authz` helper (dispatch already has one) and `requireRole(...)` the right role
   (hub → `HUB_OPERATOR`/`ADMIN`; airline → `AIRLINE_GHA`/`ADMIN`; routing/grid → `STATION_MANAGER`/`ADMIN`).
2. **City-scope**: validate the path `{hubId}`/`{cityId}` resolves to the caller's assigned hub/city
   (`User.cityId`), with `ADMIN` bypassing to all — mirror `StationDispatchController.managerCity(principal)`.
3. Optional hardening: promote coarse rules into `SecurityConfig` `requestMatchers` per path prefix so a
   missing per-controller check can't silently expose an endpoint.

Owner: TBD (original consoles built by a teammate). Not on the field-test critical path.
