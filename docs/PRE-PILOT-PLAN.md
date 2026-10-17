# Godspeed — Pre-Pilot Plan (Nov-end pilot)

Two parallel tracks for a two-person team (**Sid = S, Agniva = A**):

- **PLAN A — Core pipeline + production-readiness** (the priority). **Dated, starting Fri Oct 17.**
- **PLAN B — New feature builds** (Global ETA, OMS core, Shopify, Unicommerce, WooCommerce, customer mobile). **Generic Day 1, 2, 3… — you assign the calendar.** Runs after/alongside the back half of Plan A.

Company/legal entity + live creds land **early November**. Anything that legally needs the entity is **parked**; anything the entity only blocks from *publishing/listing* (not from *building*) proceeds now behind a flag.

### Decisions locked
- Launch on **`VAN_MEETING`** (vans + DAs at cron meeting points); defer `HUB_RETURN`.
- Monitoring: **harden detection only; defer the auto-escalation engine** (manual escalation via the control-tower board is OK for the pilot).
- Test data: **DEL↔BOM ~300–800 orders/day/direction** realistic + **one stress/soak day** toward the 10k/day ceiling.
- **Email = the universal pilot channel** (SES); SMS stays entity-blocked.
- **Global ETA** = a real build (Plan B) with a **deterministic stopgap in Plan A**.
- **Shopify + Unicommerce = must-have; WooCommerce = later; customer mobile = native build.**

### Ground truth (verified Oct 2026)
- `CLAUDE.md` status table is **stale** — all M1–M11 (+ shuttle/vision/assets) are built with real suites. Fix the map Day 1.
- **No real-DB / integration test exists** — all ~190 test classes are mock-based. The only true E2E net, the **full-day harness (PR #213), is unmerged.** #1 risk.
- Pipeline spine wired over RabbitMQ; **M6 van routing is a real OR-Tools VRP** over meeting vertices; **M5 DA list is a greedy proximity+aging sort with a correctly-enforced pre-assignment cron-feasibility gate** (`QueueReorderService.cronConstrained`, O(n²) = the scale hotspot).
- **M10 SLA + M11 exceptions built**; escalation events (`oneday.sla.events`) have **no consumer** (board-polling only); **DA no-show / cron-flight-miss triggers partly dead**.
- **Stranded branches:** #213 (E2E harness), #214 (SES email), #209 (DA onboarding), `fix/cod-ledger-null-bounds` (live COD 500).
- **Developer API + webhook ordering** works happy-path but the webhook layer is **one-attempt-then-dropped** (no retry/DLQ, in-memory = crash-loss, no test, booking fires no webhook).
- **Global ETA is a hardcoded stub** (`StubEtaAdapter` → next-day 14:00) — wrong for a one-day product.
- **Custom domain wired nowhere** (all `*.vercel.app`); doable now (no entity). **Prod is a profile-flip** but no prod Render service/DB exists in IaC; **load/capacity untested** (synchronous intake, concurrency ≈ pool of 10).

### How far from prod? (one paragraph)
The hardening *code* is ~80% written but dormant under an unproven `prod` profile; happy-path *features* are essentially all merged. The real distance is **validation, not features**: no integration/E2E proof, untested capacity, a few closeable correctness gaps (webhook reliability, ETA stub, control-tower UTC bug, COD 500, M8 scans feeding ageing), the universal-channel wiring (email), domains, and the entity-gated creds + a real prod env. Plan A closes the first set and preps the rest; entity items stay parked as loud sandbox mocks.

---

# PLAN A — Core Pipeline + Production-Readiness

**14 working days, Fri Oct 17 → Wed Nov 5** (weekends Oct 18–19, 25–26, Nov 1–2 are buffer/off). Core substantially done by Oct 30–31; cutover rehearsal + go/no-go spill into the first Nov working days (the "minimal tail").

### Priority gap register
**P0 — pilot-blocking (must land):**
1. **Integration-test foundation** — merge + stabilize the full-day harness (**#213**), put it on a **real CI gate** (Postgres + Rabbit), add first real-DB tests on booking→state-machine + the two native-SQL endpoints that shipped prod 500s (ageing, scorecards). *Root risk.* — S lead, A
2. **Monitoring tests** — `@SpringBootTest` + consumer-wiring + DB tests for M10 (`SlaEngine` RED→BREACHED→escalation-row, sweeper, 6 consumers — no test class today) and M11 (capture→RTO→M4); verify `/control-tower` + `/red-queue` reliable/complete. — S (M10), A (M11)
3. **One-day-critical triggers (detection only)** — DA no-show → case → reassign; **CRON_MISSED / FLIGHT_MISSED** emitted so they surface on the board. No auto-tier engine. — A, S
4. **Email as universal channel** — merge **#214 (SES)**, set `NOTIFY_EMAIL_PROVIDER=ses` + env/IAM, verify the `godspeed.co.in` identity; wire **email OTP** (pickup template + pass `senderEmail`; delivery inject `NotificationPort`) + a **booking-confirmation** email. — S
5. **Webhook reliability** — retry/backoff + attempt-cap + endpoint auto-disable + re-drive over PENDING rows (fix crash-loss); add a `shipment.created`/booked webhook; add replay/idempotency header; add a real **E2E test** (create key → book via API → assert signed delivery). — A
6. **ETA stopgap** — replace the next-day-14:00 stub with a deterministic rule (cutoff + flight dep/arr + ground buffers) so the one-day promise + delay alerts are credible. — S
7. **Merge stranded fixes** — `fix/cod-ledger-null-bounds`; decide #209 merge-or-park. — A
8. **Flight-selection seam** — hub uses `StubFlightAssignmentPort`, not M9. Wire M9-schedule-into-hub OR ratify a seeded flight table as the pilot contract + document. — S

**P1 — important (land if possible, else early-Nov tail):** control-tower **UTC→IST** bug (one-liner); **M8 scans** feeding ageing/timeline/dwell/SLA (wire or label as age-since-booking); verify **real DA GPS** path (`/dispatch/da/{id}/gps` → `da_gps_ping`) overrides `LocationStubService`; `SlaFlightEventsConsumer` dormant (enable+test if air-leg SLA needs it); CI gate must actually run DB-backed suites.

**P2 / parked:** auto-escalation engine (timers/tiers/notification fan-out — data exists, bounded post-pilot); M2 cost-floor in the VRP; HUB_RETURN hardening + mode-flip safety; scan-trail IDOR nit; Global ETA full engine (→ Plan B).

### Production-scale scenario catalog (Phases 3–4)
Seeded at pilot volume on DEL↔BOM; each asserts **(L)** ledger row at every scan node, **(S)** correct M4 transition, **(SLA)** correct leg reaction.
- **First-mile:** DA no-show (never-online) → reassign; DA silent mid-shift; pickup fail (not-ready/wrong-address/refused); pickup-OTP mismatch; **surge** (one tile/DA → cron-feasible prefix defers overflow + cross-territory spill); cancel pre/post-pickup (PREPAID refund vs COD); B2B credit/member-budget exceeded; after-cutoff → `scheduled_pickup` HELD.
- **Cron/hub/air:** **cron missed**; van late/breakdown → recovery van; **hub overload**; bag/handoff discrepancy; **flight delayed/cancelled/reassigned** → M7 re-bag; underweight-bag reschedule (SLA-gated); GHA fault-code (ours vs airline).
- **Last-mile:** fail attempt-1 → reattempt; attempt-2 → max → RTO; delivery-OTP / wrong-box; RTO mid-transit; COD collection + cash variance; **mixed van loop** (deliver+collect at one stop).
- **Stress day:** 10k/day burst (QueueReorder O(n²), OSRM, hub sortation, queue-depth/DLQ, Hikari pool) + soak (compressed 16h); idempotency (dup booking/scan/webhook); out-of-order + poison → DLQ; IST/UTC midnight; concurrent B2B bookings (credit race); multi-DA choreography at one vertex.

### Team split
- **S** — outbound + air + SLA-detection + harness + cutover: M4, M8, M7, M9, M2, M10, E2E/load harness + seed generator, email/SES, ETA stopgap, infra/cutover.
- **A** — field + assignment + exceptions + grid/routing + webhooks: M3, M5, M6, M11, dispatch/driver flows, COD/cash, webhook reliability.
- Each **reviews the other's lane** (knowledge transfer is a goal).

### Day-by-day (dated)
**Phase 0 — Foundation**
- **Day 1 · Fri Oct 17 (joint):** fix `CLAUDE.md` map; stand up the full-chain harness skeleton (merge/stabilize #213); build the realistic DEL↔BOM seed generator; merge `fix/cod-ledger-null-bounds` + #214 (SES).
- *Oct 18–19 — buffer.*

**Phase 1 — Deep read + as-built docs + targeted hardening** (each writes a short as-built note per module = the "know every piece" deliverable)
- **Day 2 · Mon Oct 20:** S → M10 (+tests, escalation board, UTC→IST) · A → M5 (cron gate + sequencing on real GPS; assignment/feasibility/spill tests).
- **Day 3 · Tue Oct 21:** S → M4 + M8 (state-machine + scan-ledger harness assertions) · A → M6 (VRP/custody; confirm Clarke–Wright runs in CI).
- **Day 4 · Wed Oct 22:** S → M7 + flight seam (P0-8) · A → M11 (+tests, no-show/cron-miss detection P0-3).
- **Day 5 · Thu Oct 23:** S → M9 + M2 + email OTP/booking-confirm (P0-4) · A → M3 + driver flows + on-time% phantom fix.

**Phase 2 — Cross-review + burn-down**
- **Day 6 · Fri Oct 24:** swap-lane review (`/code-review` each other's diffs); S → ETA stopgap (P0-6); A → webhook reliability (P0-5).
- *Oct 25–26 — buffer.*
- **Day 7 · Mon Oct 27:** finish webhook+ETA; green the reactor + harness + CI gate (P1); ratify open design decisions into `docs/DECISIONS.md`.

**Phase 3 — Scenario testing at pilot volume**
- **Day 8 · Tue Oct 28:** first-mile + cron scenarios.
- **Day 9 · Wed Oct 29:** hub + air scenarios.
- **Day 10 · Thu Oct 30:** last-mile + exceptions + SLA-board + idempotency; record pass/fail evidence per scenario.

**Phase 4 — Load + soak + perf**
- **Day 11 · Fri Oct 31:** stress day — burst → 10k; capture breaking points (QueueReorder, OSRM, pool, DLQ).
- *Nov 1–2 — buffer.*
- **Day 12 · Mon Nov 3:** soak (compressed 16h day) + apply targeted perf fixes.

**Phase 5 — Cutover rehearsal + go/no-go**
- **Day 13 · Tue Nov 4:** prod-profile dry-run (`SPRING_PROFILES_ACTIVE=prod`: MockGuard/ProdEnvGuard, JWT fail-fast, headers/CORS, 429, actuator readiness, migrations-from-scratch + relabel, rollback drill); go-live runbook (per-city `VAN_MEETING`).
- **Day 14 · Wed Nov 5:** **written go/no-go** against the exit gate; final burn-down; parked list + Nov owners.

### Plan-A exit gate
(1) full-chain harness green on a real CI gate; (2) M10/M11 have `@SpringBootTest`+consumer+DB coverage; (3) every scenario recorded pass or accepted parked exception; (4) stress day has a known acceptable breaking point above pilot volume; (5) email sends real mail for OTP + booking + milestones; (6) webhook retry + E2E test green; (7) prod-profile dry-run + migration + rollback pass; (8) each core module has an as-built note.

---

# PLAN B — New Feature Builds (generic Day 1, 2, 3…)

Reuse is heavy — API keys, HMAC webhooks, serviceability (`/api/grid/serviceable-at`), quote (`/api/v1/pricing/quote`), SKU-less B2B booking, wallet, white-label tracking all exist. Each feature: **scope · process · effort · entity-block · day-steps.** _Process details for Shopify/Unicommerce verified against shopify.dev + Unicommerce support Oct 2026 — reconfirm specifics at build time, platform docs move._

### B1 — Global ETA engine · ~8–12 days · **NOT entity-blocked**
- **Scope:** replace `StubEtaAdapter` with a real per-leg `EtaPort` (cutoff → first-mile → origin-hub → flight dep/arr via M9/AeroDataBox → dest-hub → last-mile); recompute at key transitions; feed the customer promise + `SHIPMENT_DELAYED` alert. Design exists: `docs/M10/M10-GLOBAL-ETA-DESIGN.md`.
- **Day-steps:** D1–2 ratify per-leg buffers with ops + finalize the model · D3–6 implement the port + projection + recompute hooks · D7–9 unit + integration tests · D10–12 wire into booking/quote/track + delay-alert, verify against the stress dataset.
- *First increment is the Plan-A stopgap; this hardens it into the real engine — do it early.*

### B2 — OMS/Integrations core (new module M12) · ~10–15 days · **NOT entity-blocked** · **prereq for B3/B4/B5**
- **Scope** (`docs/OMS-AND-STOREFRONT-INTEGRATIONS.md §5`): `external_order` model (idempotent on platform+external-id); per-merchant connector creds (encrypted); inbound webhook receivers (verify each platform HMAC → upsert); fast public **rate endpoint** (serviceability+quote); **Ready→Book** (external_order → B2B booking, wallet-debit); **status write-back** on transitions; a merchant **Orders inbox** screen in the business console.
- **Day-steps:** D1–3 schema + external_order + idempotency · D4–6 connector-cred store + rate endpoint · D7–9 inbound receiver framework + Ready→Book · D10–12 status write-back on the event lane · D13–15 Orders-inbox UI + contract tests.

### B3 — Shopify app · ~15–20 days (+ review lead time for public) · **BUILD not blocked / public listing blocked**
- **2026 process:** create the app in the **Shopify Dev Dashboard** (or **Shopify CLI** — CLI for the embedded settings UI; Dev Dashboard for API-only). **Distribution is decoupled from creation.** Choose **custom distribution** (install on specific pilot stores via link — *no App Store review*, cannot use Shopify Billing API) for launch; **public distribution** (App Store listing → app review + "Built for Shopify" standards + mandatory compliance/privacy webhooks + ongoing monitoring + business verification) is the post-entity path.
- **3 surfaces:** order ingestion (`orders/create|paid` webhooks + Admin GraphQL); **checkout rate** via **CarrierService** callback (not deprecated — but gated by the *merchant's* plan: Advanced/Plus included, Grow via annual billing/add-on, Basic/Starter excluded → confirm per merchant); fulfilment/tracking **write-back** (deep-link to white-label tracking). OAuth 2.0 per shop, offline token.
- **Day-steps:** D1–2 Partners/Dev-Dashboard account + dev store + scopes + OAuth install · D3–6 order-ingestion webhooks → M12 external_order · D7–10 CarrierService rate callback (serviceability+quote; must be fast) · D11–14 fulfilment + tracking write-back · D15–18 embedded settings screen (CLI/Polaris) + custom-distribution install on the pilot store · D19–20 end-to-end test with a real dev-store order. *(Public listing = separate post-entity track: review submission + compliance webhooks + monitoring.)*
- **Entity:** custom distribution needs **no entity, no review** → build now. Public App-Store listing → parked.

### B4 — Unicommerce integration · ~10–15 days adapter *once spec + sandbox obtained* · **START partnership/entity-blocked**
- **What it is:** Unicommerce (Uniware) is a leading Indian OMS/WMS; sellers dispatch via **Shipping Providers** configured in Uniware (Settings → Shipping Providers; serviceability + forward/reverse methods; **AWB generation in API mode**; per-seller API username/password under Connectors). We expose a **courier adapter** implementing their shipping-provider contract: serviceability, create-shipment/AWB (API mode), label/manifest, cancel, track/status, NDR/reverse.
- **Process + pending work (the unknown):** there is **no public self-serve "build your own courier" program**. To be a natively-supported courier you must **engage Unicommerce directly** for (a) their **courier-integration spec/API contract**, (b) a **commercial/partner agreement**, and (c) **sandbox + per-seller API creds**. So there is real external discovery work here: contact Unicommerce, get the program terms + spec, then build the adapter to it.
- **Day-steps (once unblocked):** D1–2 ingest their courier spec + map to our booking/tracking · D3–7 implement the adapter endpoints (serviceability, create-AWB, label/manifest, cancel) · D8–11 track/status push + NDR/reverse · D12–15 sandbox certification + fixes.
- **Entity:** getting the spec/sandbox and especially production courier onboarding generally needs a signed partnership → **likely entity-blocked**. **Action: contact Unicommerce now to confirm whether sandbox/dev creds are available pre-entity.** If yes → build now; if no → **park, but lock the spec + design now** so the ~2–3-week adapter is fast once unblocked. (Backend-only, no UI.)

### B5 — WooCommerce plugin · ~10–15 days (+ optional wp.org review) · **NOT entity-blocked** · *scheduled last (later)*
- **Scope:** WP/PHP plugin — a `WC_Shipping_Method` calling our rate endpoint at checkout + order-sync (push order to Godspeed, write tracking back); merchant pastes their Godspeed API key in settings.
- **Day-steps:** D1–3 plugin scaffold + settings (API key) · D4–8 shipping method → rate endpoint · D9–12 order-sync → M12 + tracking write-back · D13–15 package as signed `.zip` (no gate) / optional wp.org listing.
- **Entity:** none. Lowest priority.

### B6 — Customer mobile ordering app (native RN/Expo) · ~15–25 days · **BUILD not blocked / store-publish blocked**
- **Scope:** a real customer app mirroring `oneday-web/apps/customer`: auth (M1 JWT + Google/phone) → serviceability pin → quote → book (`/api/v1/b2c/shipments`) → pay (Razorpay RN SDK or hosted-checkout webview) → live tracking. Reuse the `@oneday/api` contracts + the `oneday-driver-app` Expo/RN stack.
- **Day-steps:** D1–3 Expo scaffold + auth + `@oneday/api` wiring · D4–8 serviceability + quote + booking flow · D9–13 payment + confirmation · D14–18 live tracking + polish · D19–22 EAS internal-distribution build + device QA · D23–25 bug burn-down.
- **Entity:** build is not blocked. **Publishing** needs a **Google Play org account** (`[ENTITY]`) + Apple org (D-U-N-S) → interim **EAS internal testing / APK sideload / TestFlight**. Build now, store-publish parked.

### Sequencing with 2 devs (realistic — it won't all fit in 25 days)
Total Plan B ≈ 70–100 dev-days; 2 devs × ~25 working days ≈ 50 dev-days, so some spills past the window. Priority order and lanes:
```
During Plan A (weeks 1–2): Plan B = spec/accounts only — Shopify Partners + dev store,
      contact Unicommerce for program/spec/sandbox, ratify ETA buffers with ops.
Lane S:  B1 Global ETA (D1–12)  →  B6 Mobile app (D13–25, MVP by D25; polish/publish tail)
Lane A:  B2 M12 core (D1–15)    →  B3 Shopify custom app (D16–25, finish into tail)
         B4 Unicommerce adapter → slots in Lane A IF sandbox is granted; else parked (spec locked)
Tail (beyond D25 / into entity window): B5 WooCommerce · Shopify public listing ·
      Unicommerce prod onboarding · app-store publishing · live-cred flips.
```
What realistically lands in ~25 days: **Global ETA + M12 core + Shopify custom app + a mobile MVP** (+ Unicommerce adapter *if* unblocked). WooCommerce and all *publishing/listing/onboarding* steps are the entity-window tail — none block the *builds*, only each feature's *go-live*.

---

## External actions (start now — long lead times)
Shopify **Partners / Dev-Dashboard** account + dev store + confirm each pilot merchant's carrier-rate plan · **contact Unicommerce** for courier-program terms + integration spec + sandbox creds · verify **SES** `godspeed.co.in` identity + out-of-sandbox · add **GoDaddy CNAMEs** for the subdomain scheme (`app`/apex→customer, `business.`/`admin.`/`hub.`/`station.`/`airline.`, `api.`→Render) + per-app Vercel domains + prod `NEXT_PUBLIC_API_BASE_URL` + update prod `GODSPEED_CORS_ALLOWED_ORIGINS` · provision a **prod Render service + DB** (IaC is staging-only) · (entity) Razorpay live + RazorpayX, DLT SMS, GHA/AWB, GSTIN, Google Play org.

## Explicitly parked (Nov / post-entity)
Auto-escalation engine (timers/tiers/SMS/push) · live SMS/DLT OTP · Shopify public App-Store listing · Unicommerce production courier onboarding · app-store publishing · WooCommerce (last) · HUB_RETURN hardening · M2 cost-floor in VRP.
