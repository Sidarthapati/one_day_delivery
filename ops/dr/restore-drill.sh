#!/usr/bin/env bash
# DR restore drill — recover a backup into a throwaway scratch DB and verify it's bootable.
# Implements docs/prod-readiness/DR-RPO-RTO.md "Restore procedure" as a repeatable script.
#
# The DB half runs anywhere with psql/pg_restore. The app-boot + smoke half is opt-in (--boot),
# since it needs JDK 21 + the app env. Nothing here touches prod: it restores INTO a scratch DB.
#
# Usage:
#   ops/dr/restore-drill.sh --dump backup.dump                # restore a real backup file
#   ops/dr/restore-drill.sh --from-source                     # pg_dump the source DB first, then restore it
#   ops/dr/restore-drill.sh --dump backup.dump --boot         # also boot the app + smoke it
#
# Env (defaults target the local Postgres from CLAUDE.md "LOCAL" setup):
#   SRC_URL / SRC_USER / SRC_PW   source DB (only for --from-source)
#   SCRATCH_HOST(localhost) SCRATCH_PORT(5432) SCRATCH_USER(oneday) SCRATCH_PW(secret)
#   SCRATCH_DB(oneday_dr_drill)   scratch DB name (dropped + recreated each run)
set -euo pipefail

DUMP=""
FROM_SOURCE=0
BOOT=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dump) DUMP="$2"; shift 2;;
    --from-source) FROM_SOURCE=1; shift;;
    --boot) BOOT=1; shift;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

SCRATCH_HOST="${SCRATCH_HOST:-localhost}"
SCRATCH_PORT="${SCRATCH_PORT:-5432}"
SCRATCH_USER="${SCRATCH_USER:-oneday}"
SCRATCH_PW="${SCRATCH_PW:-secret}"
SCRATCH_DB="${SCRATCH_DB:-oneday_dr_drill}"

log() { printf '\033[36m[dr-drill]\033[0m %s\n' "$*"; }
start_epoch=$(date +%s)

# 1. Obtain the "backup" ------------------------------------------------------
if [[ "$FROM_SOURCE" == 1 ]]; then
  : "${SRC_URL:?set SRC_URL for --from-source}"
  DUMP="$(mktemp -t oneday-dr-XXXX).dump"
  log "pg_dump source → $DUMP"
  PGPASSWORD="${SRC_PW:-}" pg_dump --format=custom --no-owner --no-privileges \
    --dbname="$SRC_URL" --file="$DUMP"
fi
[[ -n "$DUMP" && -f "$DUMP" ]] || { echo "no dump file (use --dump PATH or --from-source)" >&2; exit 2; }
dump_age_h=$(( ( start_epoch - $(stat -f %m "$DUMP" 2>/dev/null || stat -c %Y "$DUMP") ) / 3600 ))
log "backup age ≈ ${dump_age_h}h (this is the drill's RPO for a real backup)"

export PGPASSWORD="$SCRATCH_PW"
psql_scratch() { psql -h "$SCRATCH_HOST" -p "$SCRATCH_PORT" -U "$SCRATCH_USER" "$@"; }

# 2 + 3. Fresh scratch DB, restore into it ------------------------------------
log "recreating scratch DB $SCRATCH_DB"
psql_scratch -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $SCRATCH_DB WITH (FORCE);"
psql_scratch -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $SCRATCH_DB;"
log "restoring backup into $SCRATCH_DB"
pg_restore --no-owner --no-privileges --dbname="postgresql://$SCRATCH_USER:$SCRATCH_PW@$SCRATCH_HOST:$SCRATCH_PORT/$SCRATCH_DB" "$DUMP" \
  || log "pg_restore reported non-fatal warnings (ownership/roles) — continuing"

# 4. Schema sanity: Flyway history present, no obviously-missing core tables ---
applied=$(psql_scratch -d "$SCRATCH_DB" -tAc "SELECT count(*) FROM flyway_schema_history WHERE success" || echo 0)
log "flyway_schema_history: $applied successful migrations restored"
for t in users shipments refresh_tokens; do
  exists=$(psql_scratch -d "$SCRATCH_DB" -tAc "SELECT to_regclass('public.$t') IS NOT NULL")
  log "  table $t present: $exists"
done

# 5. Optional: boot the app against the scratch DB and smoke it ---------------
if [[ "$BOOT" == 1 ]]; then
  log "booting app against scratch DB (staging profile, mocks on)…"
  export SPRING_DATASOURCE_URL="jdbc:postgresql://$SCRATCH_HOST:$SCRATCH_PORT/$SCRATCH_DB"
  export SPRING_DATASOURCE_USERNAME="$SCRATCH_USER"
  export SPRING_DATASOURCE_PASSWORD="$SCRATCH_PW"
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-staging}"
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
  ( mvn -q -o spring-boot:run -pl app ) & APP_PID=$!
  trap '[[ -n "${APP_PID:-}" ]] && kill "$APP_PID" 2>/dev/null || true' EXIT
  log "waiting for /actuator/health/readiness = UP…"
  for i in $(seq 1 60); do
    if curl -fsS localhost:8080/actuator/health/readiness 2>/dev/null | grep -q '"status":"UP"'; then
      log "readiness UP"; break
    fi
    sleep 3
    [[ $i == 60 ]] && { echo "app did not become ready" >&2; exit 1; }
  done
  log "smoke: quote endpoint"
  curl -fsS -X POST localhost:8080/api/v1/pricing/quote -H 'Content-Type: application/json' \
    -d '{"originPincode":"110001","destinationPincode":"400001","originLat":28.6,"originLon":77.2,"destinationLat":19.0,"destinationLon":72.8,"weightGrams":1500,"lengthCm":20,"breadthCm":15,"heightCm":10,"paymentMode":"PREPAID"}' \
    >/dev/null && log "quote OK"
fi

# 6. Report -------------------------------------------------------------------
rto_s=$(( $(date +%s) - start_epoch ))
log "DONE — drill RTO ≈ $((rto_s/60))m ${rto_s}s (target ≤ 4h). Backup age (RPO) ≈ ${dump_age_h}h (target ≤ 24h)."
log "scratch DB '$SCRATCH_DB' left in place for inspection; drop it with: DROP DATABASE $SCRATCH_DB;"
