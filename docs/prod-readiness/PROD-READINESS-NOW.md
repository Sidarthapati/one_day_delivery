# Godspeed — Prod-Readiness: What We Can Do **Now**

Companion to [`PROD-READINESS-PLAN.md`](./PROD-READINESS-PLAN.md) (the 6-gate master plan).
This doc re-scopes that plan for our **current reality**: the company isn't a legal entity yet, we
don't have the live creds (Razorpay/SMS/KYC/GHA/domain/GST), and **we're still actively testing on
staging** — we are *not* moving to prod now.

**Purpose:** close as much of the pre-production-readiness checklist as possible *now*, so that when
cutover day comes it's a **minimal change** (ideally a profile flip), under two hard constraints:

1. **Not blocked by company formation / missing creds.**
2. **Must not block ongoing testing.**

**How to read the verdict tags:**

| Tag | Meaning |
|-----|---------|
| 🟢 **NOW** | Do this pass; behavior is active (and safe) in staging. |
| 🟡 **NOW-dormant** | Build now, keep **inert under the `prod` profile**; staging behavior unchanged. Cutover just activates it. |
| 🔵 **DONE** | Already satisfied in code today (Aug-19 audit). |
| 🟠 **DEFER · ENTITY** | Legally needs the company to exist / a real cred. Stays a **loudly-flagged sandbox mock**. |
| 🟣 **DEFER · env/later** | No entity blocker, but scheduled to Branch 2 (heavier) or needs a prod-like environment. |

---

## The design principle (non-negotiable)

**Profile-gated dormant hardening.** We build the prod-hardened behavior now but keep it inert under a
`prod` profile that we do **not** activate. `staging` stays permissive: mocks on, `DemoAuthFilter` on,
rate limits relaxed/off, CORS includes localhost. Everything is **env/profile-driven with
staging-safe defaults**. Cutover = `SPRING_PROFILES_ACTIVE=prod` + prod env vars.

> Acceptance gate on **every** change in this pass: *staging still boots on the `staging` profile with
> mocks ON and the existing test suite passes.* If a change would block testing, it goes behind the
> `prod` profile.

---

## Classification — the full checklist

### 1. Security
| Item | Current state (evidence) | Verdict | Where |
|------|--------------------------|:------:|------|
| Secrets only from server; fail-fast if missing; rotate committed keys | `jwt.secret` committed literal, no `${JWT_SECRET}` (`app/.../application.properties:41`) | 🟢 NOW | B1.2 |
| No test/fake code in prod; refuse start if fake enabled | mocks are `@Profile("!prod")` but no boot-guard; deploy runs `staging` → mocks live | 🟡 NOW-dormant | B1.1 |
| Authentication on every protected endpoint | JWT filter present; `DemoAuthFilter` injects ADMIN under non-prod | 🟢 NOW (audit) | B1.1 |
| Authorization on every action (role + city/tenant) | only `StationDispatchController` + auth `DaController` scope; hub/routing/grid/airline authenticated-only | 🟢 NOW | B1.4 + follow-up |
| IDOR / BOLA protection | ownership checks exist in orders; path-param scoping gaps on `{hubId}`/`{cityId}` | 🟢 NOW | B1.4 / B2 pentest |
| Rate limiting | **absent** (no bucket4j/resilience4j RateLimiter) | 🟢 NOW | B1.3 |
| HTTPS everywhere | Render/Vercel TLS by platform; no app-side HSTS/redirect | 🟢 NOW (headers) / 🟠 preload=domain | B1.4 |
| Input validation | broad `@Valid` + jakarta constraints (~30 controllers) | 🔵 DONE (spot-audit gaps) | — |
| Security headers + locked CORS | no `.headers()`; CORS `allowedOriginPatterns("*")` | 🟢 NOW | B1.4 |
| Dependency + container scanning | CodeQL + Trivy + gitleaks, per-PR + scheduled | 🔵 DONE | — |
| Penetration test before launch | none | 🟣 ZAP now / 🟠 human pentest deferred | B2 / ENTITY |
| Security disclosure contact | none | 🟢 NOW | B1.9 |

### 2. Authentication & session
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Token/session/OTP expiry configured | JWT 8h; OTP has expiry | 🟢 NOW (tighten) | B1.2 |
| Refresh-token / session revocation | **none** | 🟣 NOW-Branch2 | B2 |
| Logout correct | stateless JWT; client-drop | 🟢 NOW (verify) | B2 |
| Password reset / OTP reset secure | OTP path exists; reset delivery needs email | 🟢 NOW (logic) / 🟠 delivery | B2 / ENTITY |
| OTP protections (expiry/attempts/replay/rate) | partial; rate-limit missing | 🟢 NOW | B1.3 |
| Admin MFA | none | 🟣 optional-later | B2 |
| Account-enumeration minimized | partial | 🟢 NOW | B2 |

### 3. Data & privacy
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Sensitive data encrypted at rest | Render Postgres encrypts at rest (platform) | 🔵 DONE (document) / 🟣 column-level optional | docs |
| DPDP obligations mapped | not documented | 🟢 NOW (doc) | B1.9 |
| Data access need-to-know + logged | app `AuditLog` exists; DB-team access = ops | 🟢 NOW (app) / 🟠 team/RBAC | B1.9 |
| Audit log of sensitive actions | `AuditLog` present; extend to refunds/permission/admin | 🟢 NOW | B1.6/B1.9 |
| Backups encrypted + access-controlled | Render-managed (unverified) | 🟢 NOW (document) / restore=B2 | docs / B2 |
| Data retention & deletion rules | none | 🟢 NOW (doc) | B1.9 |
| Secrets/PII absent from logs | structured logging; needs a scrub pass | 🟢 NOW | B1.6 |

### 4. Database & integrity
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Critical ops transactional | `@Transactional` throughout | 🔵 DONE | — |
| Idempotency for critical ops | `IdempotencyFilter` (SHA-256 body, 24h) + telemetry exempted | 🔵 DONE | — |
| Concurrency safe | `SELECT FOR UPDATE` on wallet/credit/ref/OTP | 🔵 DONE | — |
| DB constraints enforced | Flyway-owned FKs/uniques | 🔵 DONE (review) | B1.8 |
| Prod migrations tested / rollback | `validate-on-migrate=false`; no dry-run | 🟢 NOW (flip) / drill=B2 | B1.8 / B2 |
| Indexes / slow queries reviewed | not profiled | 🟣 NOW-Branch2 | B2 (load test) |

### 5. Reliability
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Timeouts on every external call | OSRM/grid `new RestTemplate()` (∞); RestClients + HttpClients missing read timeouts | 🟢 NOW | B1.5 |
| Circuit breakers | only orders booking (`ResilienceConfig`) | 🟢 NOW | B1.5 |
| Consistent error handling | advice in auth/orders/pricing/hub only; no leaks target | 🟢 NOW | B1.6 |
| DB connection limits sized | Hikari defaults (10), unset | 🟢 NOW | B1.8 |
| Graceful under overload | none beyond defaults | 🟣 NOW-Branch2 | B2 (load) |
| External dependency failure modes tested | none | 🟣 NOW-Branch2 | B2 |

### 6. Queues & async
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Retry policies defined | Rabbit retry/DLQ topology present | 🔵 DONE (verify) | B2 |
| Consumers idempotent | mostly; audit | 🟢 NOW (audit) | B2 |
| Dead-letter handling | DLQ topology + ADMIN replay tool | 🔵 DONE | — |
| Poison messages can't block | DLQ + maxAttempts | 🔵 DONE (verify) | B2 |
| Queue depth / lag alerts | none | 🟢 NOW (accounts) | B1.7 / B2 alerts |
| Ordering documented | not documented | 🟢 NOW (doc) | B1.9 |
| Retention / TTL defined | partial | 🟢 NOW | B1.8 |

### 7. Payments & external integrations
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Webhook signatures verified | Razorpay HMAC verify present | 🔵 DONE | — |
| Duplicate webhooks harmless | idempotent handling | 🔵 DONE (verify) | B2 |
| Payment/app failure states handled | OD-7 refund-on-failure exists | 🔵 DONE (verify) | B2 |
| Production credentials isolated | mock gateway `live=false`; no prod-guard | 🟡 NOW-dormant (mechanism) / 🟠 live keys | B1.1 / ENTITY |
| Provider rate limits monitored | n/a (sandbox) | 🟠 DEFER · ENTITY | ENTITY |
| External credentials rotatable | env-based | 🔵 DONE | — |

### 8. Observability & alerting
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Metrics (latency/traffic/errors/saturation) | no actuator/prometheus; `SimpleMeterRegistry` in dispatch only | 🟢 NOW | B1.7 |
| Alerting | none | 🟢 NOW (accounts) | B1.7 / B2 rules |
| External uptime monitor | none | 🟢 NOW | B1.7 |
| Error tracking | none (no Sentry) | 🟢 NOW | B1.7 |
| Dashboards | none | 🟢 NOW | B1.7 |
| Logging (structured, searchable) | JSON logback + correlation IDs; Axiom drain **not wired** | 🔵 DONE (wire drain) | B1.7 |
| Distributed tracing | none | 🟣 NOW-Branch2 | B2 |

### 9. Recovery / DR
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Backups exist AND restore tested | Render backups unverified | 🟢 NOW (drill) | B2 |
| Recovery targets (RPO/RTO) | undefined | 🟢 NOW (doc) | B1.9 |
| Recovery steps documented | none | 🟢 NOW (doc) | B1.9 |
| DR drill completed | none | 🟣 NOW-Branch2 | B2 |

### 10. Deployment & CI/CD
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Prod builds require passing CI | CI present; e2e excluded | 🟢 NOW (e2e in CI = B2) | B2 |
| Production/staging separation | staging isolated; no prod env yet | 🟡 NOW-dormant (prod config) | B1.1 |
| Health-gated deploys | Render healthcheck = shallow `/auth/health` | 🟢 NOW (deep readiness) | B1.7 |
| Tested rollback | Render rollback exists, untested | 🟢 NOW (test) | B2 |
| Migrations deployment-safe | undocumented | 🟢 NOW (doc) | B1.9 |
| Deployment audit trail | Render history + git SHA | 🔵 DONE (document) | B1.9 |
| Environment validation at startup | none | 🟢 NOW | B1.1 |

### 11. Operations
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Runbooks | none | 🟢 NOW (doc) | B1.9 |
| On-call + escalation | none | 🟢 NOW (doc) | B1.9 |
| Instant access revocation | API-key revoke exists; user disable | 🟢 NOW (verify) | B2 |
| Least privilege for team | ops/Render RBAC | 🟠 partial · ops | ops |
| Incident response process | none | 🟢 NOW (doc) | B1.9 |

### 12. Performance, capacity & cost
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Load test passed | none | 🟣 NOW-Branch2 | B2 |
| Capacity limits known | estimated only | 🟣 NOW-Branch2 | B2 |
| Autoscaling limits | Render config; needs paid plan | 🟠 partial · env | B2 |
| Third-party cost protection | Maps quota only | 🟢 NOW (Maps) / 🟠 live providers | B2 / ENTITY |
| Storage / log growth monitored | none | 🟢 NOW (accounts) | B1.7 |
| Expected prod cost estimated | partial (in master plan) | 🟢 NOW (doc) | B1.9 |

### 13. Frontend
| Item | Current state | Verdict | Where |
|------|---------------|:------:|------|
| Critical journeys pass E2E | manual only | 🟣 NOW-Branch2 | B2 |
| No internal errors exposed | needs error-envelope + FE states | 🟢 NOW | B1.6 |
| Loading/timeout/failure states usable | partial | 🟣 NOW-Branch2 | B2 |
| Mobile/responsive tested | partial | 🟣 NOW-Branch2 | B2 |
| Accessibility baseline | none | 🟣 NOW-Branch2 | B2 |
| Analytics/event tracking verified | none | 🟣 optional | B2 |

### 14. Final Go/No-Go
The go/no-go gate itself runs at cutover (see master plan §6). Its criteria drive the work above.

---

## Already DONE (don't redo)
CI CodeQL + Trivy + gitleaks (per-PR + scheduled) · telemetry idempotency exemption
(`/api/v1/shuttle|van/*/telemetry`) · idempotency filter (SHA-256 body, 24h, replay) ·
transactions + `SELECT FOR UPDATE` · broad `@Valid` input validation · structured JSON logging +
correlation IDs + `AuditLog` · DLQ topology + ADMIN replay · Razorpay webhook HMAC verify ·
env-based (rotatable) secrets · Postgres encryption-at-rest (platform).

---

## Deferred — `[ENTITY]` (needs the company to exist / a real cred)
Each stays a **loud sandbox mock that refuses to masquerade as live** (boot-flagged; only an explicit
`godspeed.mocks.<x>.enabled=true` override runs it, and it logs LOUDLY).

| Item | Blocked on | Interim | Flips live when |
|------|-----------|---------|-----------------|
| Razorpay live keys + KYC | Company KYC | `rzp_test_*` mock, `live=false` | `RAZORPAY_*` live env |
| DLT SMS + real email delivery | Entity + TRAI DLT / domain auth | `DevOtpController`, log-sink senders | DLT IDs / SendGrid domain |
| GHA / AWB credentials | Airline partner + entity | `StubGhaAdapter` / sandbox | Partner creds |
| Company domain + edge WAF + HSTS-preload | Domain purchase | Render/Vercel default domains + app headers | Domain + WAF |
| Google Play org account | Company account | internal-testing / sideload | Play org + signing |
| GST / legal invoice IDs | Registration | placeholder invoice fields | GSTIN issued |
| Live-provider quota/cost monitoring | live providers | n/a | providers live |
| **Human penetration test** | cost/entity | automated **OWASP ZAP** (Branch 2) | budget/entity |

---

## Branch execution tracker

### Branch 1 — `f-prod-hardening` (off `main`) · safe hardening, dormant-in-prod
- [x] **B1.1** prod profile (`application-prod.yml`) + `MockGuard` boot-guard + startup env validation (`ProdEnvGuard`) — guard unit tests green
- [x] **B1.2** JWT secret from `${JWT_SECRET}` + fail-fast under prod; committed literal downgraded to a dev-only fallback that `ProdEnvGuard` rejects
- [x] **B1.3** rate limiting (dependency-free in-memory token-bucket `RateLimitFilter`; `godspeed.ratelimit.*`; off in staging, on in prod; 429 + `Retry-After`; login/otp/register per-IP + per-API-key) — filter unit tests green *(bucket4j swapped for a zero-dependency token bucket to keep the supply-chain surface unchanged; Redis-backed multi-instance = Branch 2)*
- [x] **B1.4** security headers (nosniff/frame-DENY/referrer always on; HSTS+CSP prod-only) + `godspeed.cors.allowed-origins` (prod Vercel-only; staging + localhost/`*.vercel.app`)
- [x] **B1.5** external-call timeouts: OSRM (routing+grid, config-driven `connect/read-timeout-ms`), RazorpayX, KYC (both builders), AeroDataBox now bounded via `ClientHttpRequestFactorySettings`; SMS/email/webhook `HttpClient`s already had connect+request timeouts. *Circuit breakers remain scoped to orders-booking (`ResilienceConfig`); broad CB extension deferred — timeouts already remove the thread-exhaustion failure mode.*
- [x] **B1.6** uniform error envelope — scoped `@RestControllerAdvice` (RFC-7807 ProblemDetail, extends `ResponseEntityExceptionHandler`) added to dispatch/routing/grid/airline/shuttle/sla/barcode (airline maps its domain not-founds → 404); `server.error.include-*: never` pinned as the no-leak guarantee; dead `orders/api/GlobalExceptionHandler` shell deleted; security exceptions rethrown so 401/403 are never masked as 500. *(Deep PII-in-logs scrub audit → Branch 2.)*
- [x] **B1.7 (code)** actuator + `micrometer-registry-prometheus` in `app`; deep health with `readiness`(=app+DB) / `liveness` probe groups; `/actuator/health/**` public (details hidden), all other actuator endpoints behind auth; `render.yaml` healthcheck → `/actuator/health/readiness`. **Remaining = account wiring (no code):** point Grafana Cloud at `/actuator/prometheus`; add Sentry SDK + DSN once the account exists (inert without a DSN, so deferred to avoid dead supply-chain surface); uptime monitor on the staging URL; Render→Axiom log drain.
- [x] **B1.8** Hikari pool (`maximum-pool-size`/`minimum-idle`/timeouts, env-overridable) + Tomcat thread bounds; `validate-on-migrate=true` in `application-prod.yml` (staging stays `false` for the known grid checksum drift); API-key `lastUsedAt` write throttled to ≤1/5min (was a DB write per API call on the hot auth path). **⚠️ Finding:** migrations don't apply cleanly to a *from-scratch* DB — `V1_12`(auth) version-sorts before `V10`(onboarding_requests create), so a fresh prod DB fails. Render/staging only ever accreted migrations in commit order via `out-of-order=true`. Renumber/reorder before a fresh-prod cutover — tracked for the go-live migration test (Gate in master plan / Branch 2).
- [x] **B1.9** `/.well-known/security.txt` (RFC 9116, permitted in `SecurityConfig`) + `SECURITY.md`; docs: `RUNBOOKS.md` (deploy/rollback/incident/DLQ-replay/on-call), `DATA-PROTECTION-AND-RETENTION.md` (DPDP map + retention), `DR-RPO-RTO.md`, `PROD-COST-ESTIMATE.md`. *(Contact is a placeholder domain until entity.)*

### Branch 2 — `f-prod-hardening-depth` · heavier (still no entity/creds)
- [ ] refresh-token rotation + revocation; cut 8h TTL
- [ ] distributed tracing (Micrometer Tracing + OTel → Grafana Tempo)
- [ ] alerting rules (golden signals + DLQ growth + payment-fail + broker disconnect)
- [ ] k6 load + burst test → capacity plan; set pool/threads from data
- [ ] Playwright FE E2E (6 consoles) + driver-app Maestro; enable e2e in CI (Postgres service)
- [ ] DR restore drill (Render backup → scratch DB → boot); tested rollback
- [ ] scan-flow field checklist pass; OWASP ZAP baseline

---

## Verification (applies to every batch)
- `mvn clean install` green; **staging boots on `staging` with mocks ON and existing tests pass** (the
  "doesn't block testing" gate).
- Local `-Dspring.profiles.active=prod` + required env proves: fail-fast on missing/weak `JWT_SECRET`;
  boot **refuses** with any mock enabled; no mock endpoint reachable; security headers present; CORS
  rejects an unknown origin; `429` on brute force; unauthorized cross-city → 403; `/actuator/health`
  deep-OK.
- Observability: a Sentry test event lands; uptime monitor green; a Grafana panel shows live metrics.
- `render.yaml` stays `SPRING_PROFILES_ACTIVE=staging` for the entire pass — nothing here ships to prod.

---
*Godspeed · prod-readiness "do-now" · created 2026-08-19 · companion to `PROD-READINESS-PLAN.md` · living doc.*
