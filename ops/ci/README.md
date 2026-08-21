# CI: e2e-in-CI

The main CI build (`.github/workflows/ci.yml`) runs the mock suite with `-DexcludedGroups=e2e`
because it has no database. `.github/workflows/e2e.yml` runs the excluded `@Tag("e2e")` suites
(auth/orders/dispatch) against a real Postgres 16 service. Manual (`workflow_dispatch`), not on the
PR gate.

## How the schema is built (real Flyway, from scratch)

The module e2e tests use `ddl-auto=validate` with module-scoped Flyway (auth's Flyway is disabled),
so they need the full schema **and** `flyway_schema_history` already present. The e2e job builds both
by flattening every module's main-DB migration into one location and running **real Flyway migrate**
— which applies them in version order and records history with correct checksums. No committed schema
dump is involved anymore.

This works because the migration set is now **ordering-correct from scratch** (see below). Verified
locally: all 123 migrations apply cleanly in order into a fresh Postgres 16.

## The migration-ordering fix (Route 1 — done)

Previously a fresh DB could not be migrated: `V1_12` (auth) `ALTER`ed `onboarding_requests`, but that
table was created in `V10` and phone-column added in `V11` — and Flyway sorts `1.12 < 10 < 11`, so the
alter ran before the create. Render/staging never hit it (they accreted migrations in commit order).

**Fix:** the two mis-numbered onboarding migrations were renamed down into the `1.x` sequence so they
precede the auth alters:

```
V10 → V1_11_1__create_onboarding_requests
V11 → V1_11_2__add_phone_to_onboarding_requests
```

Order is now `1.11.1 (create) < 1.11.2 (phone) < 1.12 (b2b) < 1.13 (volume)` — correct from scratch.
File contents are unchanged, so **checksums are unchanged**; only the version + script name change.

### Applying the fix to an already-migrated DB

Existing DBs (staging, any dev DB) have `flyway_schema_history` rows for the old versions `10`/`11`.
Because Flyway tracks a migration's identity by version, those rows must be relabeled to match the
renamed files **before** the renamed migrations deploy — otherwise Flyway sees the old versions as
"missing" and the new versions as "pending" (and tries to re-create existing tables). The one-time
relabel is in [`relabel-onboarding-migrations.sql`](./relabel-onboarding-migrations.sql).

**Sequencing (per DB): run the relabel first, then deploy the renamed migrations.** Fresh DBs (CI,
future prod) need nothing — they build correctly from scratch.
