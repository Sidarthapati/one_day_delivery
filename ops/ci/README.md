# CI: e2e-in-CI prerequisite

The main CI build (`.github/workflows/ci.yml`) runs the mock suite with `-DexcludedGroups=e2e`
because it has no database. `.github/workflows/e2e.yml` runs the excluded `@Tag("e2e")` suites
(auth/orders/dispatch) against a real Postgres 16 service — but it is **manual-only** until the
prerequisite below is met.

## Why it can't just `flyway migrate` a fresh DB

The module e2e tests deliberately don't build the schema themselves:

- `auth` test → Flyway **disabled**, `ddl-auto=validate` (expects the schema to already exist).
- `orders` / `dispatch` tests → Flyway **scoped to that one module's** migrations only, with
  `validate-on-migrate=false`, `ddl-auto=validate`. They assume the *other* modules' tables
  (`users`, grid, onboarding, …) are already present — because on the shared dev DB they always are.

So on a truly empty DB, orders' FK to `users` (and similar) has nothing to point at, and — the real
blocker — **the full migration set doesn't apply from scratch in version order**: `V1_12` (auth,
which ALTERs `onboarding_requests`) sorts before `V10` (top-level, which CREATEs
`onboarding_requests`). `out-of-order=true` doesn't help: a single migrate pass still runs ascending,
so `V1_12` runs before `V10` and fails. Render/staging never hit this because they accreted
migrations in commit order over time.

## Status: baseline committed ✅

`ops/ci/schema-baseline.sql` has been generated (pg_dump of a fully-migrated DB: full schema + 116
`flyway_schema_history` rows) with the Branch 2 `refresh_tokens` (V1_16) appended, and **verified to
load cleanly into a fresh Postgres 16** (77 tables, 117 history rows, `refresh_tokens` present). The
`E2E` workflow is therefore unblocked — trigger it (`workflow_dispatch`) to run the DB-backed suites.

## The two ways to (re)build the baseline

1. **Commit a schema baseline** (what's in place now — low-risk, no change to staging).
   Regenerate from a cleanly-migrated DB when migrations change:
   ```bash
   pg_dump --no-owner --no-privileges --schema-only \
     --dbname="$CLEAN_DB_URL" > ops/ci/schema-baseline.sql
   # ensure it includes the flyway_schema_history rows:
   pg_dump --no-owner --no-privileges --table=flyway_schema_history \
     --dbname="$CLEAN_DB_URL" >> ops/ci/schema-baseline.sql
   ```
   The e2e job loads this, so the scoped Flyway runs find their migrations already recorded (no-op)
   and `validate` has a schema. Regenerate when migrations change. *(A "cleanly-migrated DB" means one
   built in dependency order — e.g. a current staging restore, since staging is correctly migrated.)*

2. **Renumber the migrations** so the set applies from scratch in order (the real fix, **deferred to
   pre-cutover** because it changes applied versions on staging/prod and needs a coordinated
   `flyway repair`). Tracked in `docs/prod-readiness/PROD-READINESS-NOW.md` §B1.8 and the master plan
   go-live migration gate.

Until one is done, the e2e workflow errors out at the "Load schema baseline" step with a pointer here.

## Credentials note

The module test ymls disagree on the DB password (`auth` uses `oneday`, `orders`/`dispatch` use
`secret`). The Postgres service runs with `POSTGRES_HOST_AUTH_METHOD=trust` so the password value is
ignored and both connect as user `oneday` to db `oneday`.
