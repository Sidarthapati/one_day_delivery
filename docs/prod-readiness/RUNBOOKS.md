# Godspeed — Operational Runbooks

Practical, do-this-now procedures for deploy, rollback, incident response, DLQ replay, and on-call.
Companion to `PROD-READINESS-NOW.md`. Infra: backend on **Render** (single Docker web service),
frontends on **Vercel**, Postgres 16 (Render-managed), RabbitMQ (**CloudAMQP**), logs → **Axiom**.

> Placeholders in ALL-CAPS (`RENDER_SERVICE_ID`, on-call names, etc.) get filled once the entity/team
> exists. Nothing here needs company formation to rehearse on staging.

---

## 1. Deploy (backend)

1. Merge to `main` (CI green: `mvn install -DexcludedGroups=e2e` + CodeQL/Trivy/gitleaks).
2. Render: `autoDeploy: false` — trigger the deploy manually (dashboard → *Manual Deploy* → the SHA,
   or the Render API). Staging runs `SPRING_PROFILES_ACTIVE=staging`.
3. Watch the deploy log for `Started OneDayDeliveryApplication` and Flyway `Successfully applied`.
4. Health-gated: Render waits on `healthCheckPath=/actuator/health/readiness` (app + DB) before
   routing traffic. A failing readiness check aborts the rollout — the previous version keeps serving.
5. Smoke: `GET /actuator/health` → `UP`; one real login; one booking quote.

**Prod cutover (future):** flip `SPRING_PROFILES_ACTIVE=prod` and supply the prod env vars
(`JWT_SECRET`, `SPRING_DATASOURCE_*`, `CLOUDAMQP_URL`, `GODSPEED_CORS_ALLOWED_ORIGINS`,
`CONSOLIDATOR_DATASOURCE_*`). The app **refuses to boot** if a mock bean is active, the JWT secret is
weak/placeholder, or CORS is a wildcard (`MockGuard`/`ProdEnvGuard`).

## 2. Rollback (backend)

- **Fast path:** Render → *Rollbacks* → select the last-good deploy → *Redeploy*. This restores the
  previous image; it does **not** revert the database.
- **If a migration is the problem:** rolling the app back does not undo a Flyway migration. Prefer a
  forward-fix migration. Only restore the DB from backup (§ DR doc) if data is corrupted — this loses
  writes since the backup and is a last resort.
- Confirm rollback via `/actuator/health/readiness` + a smoke login.

## 3. Incident response

1. **Detect** — uptime monitor alert, Grafana alert (once wired), or a report.
2. **Triage severity** — SEV1 = platform down / data loss / money mis-move; SEV2 = degraded
   (one console/flow); SEV3 = minor.
3. **Assign** an incident lead (on-call, §6). One person coordinates; one channel.
4. **Mitigate first** — roll back (§2), disable the failing path, or throttle. Restore service before
   root-causing.
5. **Diagnose** — Axiom: filter by `parcel_id` / correlation id (`AuditLog`/`LogContext`). Check
   `/actuator/health` components, CloudAMQP queue depths, Render metrics.
6. **Resolve + verify** with smoke checks.
7. **Post-incident** — within 48h write a blameless note: timeline, root cause, fix, prevention.

## 4. DLQ replay (stuck async messages)

Every consumer has a dead-letter queue (`*.dlq`) with a bounded retry (3× per `application.yml`).

1. **Inspect** the real bus, not code/tests: `rabbitmqadmin -N cloudamqp list queues name messages`
   (config `~/.rabbitmqadmin.conf`, auth via `CLOUDAMQP_URL`). Non-zero `*.dlq` = poison/failed msgs.
2. **Read** a sample: `rabbitmqadmin -N cloudamqp get queue=<name>.dlq count=5 requeue=true`.
3. **Fix the cause** (bad payload, downstream down, `__TypeId__` mismatch — see the
   `MessagingConfig` DefaultClassMapper `*` note in the event-bus docs).
4. **Replay** via the ADMIN replay tool (`DispatchDlqController` and peers) once the cause is cleared,
   or shovel `<name>.dlq` → `<name>` in the CloudAMQP UI.
5. Watch the main queue drain to 0 and confirm the business effect landed.

## 5. Common quick fixes

- **App won't boot, "Could not resolve placeholder CONSOLIDATOR_DATASOURCE_URL"** → set the
  `CONSOLIDATOR_DATASOURCE_*` env vars (no localhost fallback outside the `dev` profile — by design).
- **All `@RabbitListener`s silent** → check `MessagingConfig` DefaultClassMapper is `*`.
- **429s on auth** → rate limiting is on (`godspeed.ratelimit.enabled`); expected in prod, relax the
  ceilings if legitimate traffic is being throttled.
- **Readiness DOWN but app up** → it's the DB (readiness = app + Postgres). Check the datasource; the
  broker is deliberately excluded from readiness.

## 6. On-call & escalation (TEMPLATE — fill at team formation)

| Role | Who | Contact |
|------|-----|---------|
| Primary on-call | TBD | TBD |
| Secondary | TBD | TBD |
| Escalation (owner) | TBD | TBD |

- Rotation: weekly. Ack SLA: SEV1 15 min, SEV2 1h, SEV3 next business day.
- Access to revoke fast: Render (deploy/rollback + env), CloudAMQP, Vercel, DB, API keys
  (`/auth` admin key revoke), user disable.
