# DR drill & rollback

Operational scripts behind [`../../docs/prod-readiness/DR-RPO-RTO.md`](../../docs/prod-readiness/DR-RPO-RTO.md).

## Restore drill — [`restore-drill.sh`](./restore-drill.sh)

Recovers a backup into a throwaway scratch DB and verifies it's bootable, then reports the actual
RPO (backup age) and RTO (wall-clock). **Never touches prod** — it restores *into* a scratch DB.

```bash
# Rehearse the DB restore against a real backup file (safe anywhere with psql/pg_restore):
ops/dr/restore-drill.sh --dump path/to/backup.dump

# Or dump the current source DB first, then restore it (end-to-end rehearsal):
SRC_URL="postgresql://user:pw@host:5432/db" ops/dr/restore-drill.sh --from-source

# Add --boot to also start the app against the restored DB and smoke it (needs JDK 21):
ops/dr/restore-drill.sh --dump backup.dump --boot
```

Why restore a real backup (not a fresh build): a from-scratch empty DB currently trips the
`V1_12`-before-`V10` migration ordering issue, so DR must recover an actual backup — which is the
realistic scenario anyway. The drill asserts `flyway_schema_history` and the core tables
(`users`, `shipments`, `refresh_tokens`) came back.

Record the reported RPO/RTO against the targets in `DR-RPO-RTO.md` after each drill.

## Rollback

A bad deploy is a **redeploy of the previous image**, not a restore — see
[`../../docs/prod-readiness/RUNBOOKS.md`](../../docs/prod-readiness/RUNBOOKS.md) §2 (Rollback).
Order of preference:

1. **App-only bad deploy** (no schema change) → Render → redeploy the previous successful deploy.
   Fastest; zero data loss.
2. **Bad migration** → forward-fix with a new migration if possible (migrations are append-only;
   don't hand-edit history). Only if unrecoverable, restore the last backup (loses ≤ RPO) via the
   drill script pointed at prod-restore infra.
3. **Data corruption** → restore backup into a new instance, repoint `SPRING_DATASOURCE_URL`.

The refresh-token change (B2.1) is additive (new table, appended DTO field), so rolling the app back
one version is safe — old tokens simply stop being issued; existing access JWTs keep working.
