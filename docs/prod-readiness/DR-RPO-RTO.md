# Godspeed — Disaster Recovery: RPO/RTO & Restore

Recovery targets and the restore procedure. The **restore drill itself** (recover a backup into a
scratch DB and boot against it) is scheduled in Branch 2 — this doc defines the targets and steps so
the drill has something to validate against.

## Targets

| Metric | Target (pilot) | Notes |
|--------|----------------|-------|
| **RPO** (max data loss) | ≤ 24h | Render daily automated Postgres backups. Tighten with PITR/WAL on a paid plan for GA. |
| **RTO** (time to restore service) | ≤ 4h | Restore backup + redeploy app + smoke. |
| Backup frequency | Daily (managed) | Verify retention window in the Render dashboard. |
| Backup encryption | Platform-managed, encrypted at rest | Document/confirm; access-controlled to owners. |

## What can fail & the response

| Failure | Response | Data loss |
|---------|----------|-----------|
| App instance crash/bad deploy | Render auto-restart; else rollback (`RUNBOOKS §2`) | none |
| Bad migration / data corruption | Forward-fix migration; last resort restore from backup | back to last backup (≤ RPO) |
| Postgres outage | Wait on Render recovery; if lost, restore backup to a new instance | ≤ RPO |
| Broker (CloudAMQP) outage | App keeps serving HTTP (broker is lazy, excluded from readiness); messages queue/retry; DLQ replay after | none (async delayed) |
| Region outage | Restore backup to a new region + redeploy; repoint DNS | ≤ RPO |
| Secret/key compromise | Rotate `JWT_SECRET` + DB/broker creds + API keys; force re-auth | none |

## Restore procedure (rehearse on staging)

1. Provision a fresh Postgres (new Render instance or scratch DB).
2. Restore the latest backup into it (Render dashboard *Restore*, or `pg_restore` of the dump).
3. Point a test app at it: `SPRING_DATASOURCE_URL=...` (+ `CONSOLIDATOR_DATASOURCE_*`).
4. Boot; confirm Flyway reports the schema **current** (no pending) and `/actuator/health/readiness`
   is `UP`.
   > ⚠️ Do **not** rely on a from-scratch migration build for DR — restore a real backup. A fresh
   > empty DB currently hits the `V1_12`-before-`V10` ordering issue (see `PROD-READINESS-NOW.md §B1.8`).
5. Smoke: login, a booking, a tracking read.
6. Record actual RPO (backup age) and RTO (wall-clock) vs targets; file gaps.

## Follow-ups

- Run the first restore drill (Branch 2) and record real RPO/RTO.
- Evaluate PITR/WAL archiving for a tighter RPO at GA.
- Automate a monthly restore-verification.
