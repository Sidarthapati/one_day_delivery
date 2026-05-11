# M1 — Role–Permission Reference

> **This document is the design specification** for role–permission assignments. `Role.java` must implement it — if they conflict, fix the code.
>
> **12 roles:** ADMIN · STATION\_MANAGER · SUPERVISOR · HUB\_OPERATOR · DELIVERY\_ASSOCIATE · VAN\_DRIVER · CRON\_DRIVER · CALL\_CENTER\_AGENT · B2B\_USER · B2C\_CUSTOMER · C2C\_CUSTOMER · AIRLINE\_GHA

---

## Permission Index

| Permission | Roles |
|---|---|
| `shipment:create` | B2B\_USER, B2C\_CUSTOMER, C2C\_CUSTOMER |
| `shipment:view` | ADMIN, SUPERVISOR, HUB\_OPERATOR, CALL\_CENTER\_AGENT |
| `shipment:view:own` | B2B\_USER, C2C\_CUSTOMER |
| `shipment:view:city` | STATION\_MANAGER |
| `shipment:view:assigned` | DELIVERY\_ASSOCIATE, VAN\_DRIVER, CRON\_DRIVER |
| `shipment:track:own` | B2C\_CUSTOMER, C2C\_CUSTOMER |
| `shipment:override` | ADMIN |
| `shipment:reschedule` | CALL\_CENTER\_AGENT |
| `hub:scan` | HUB\_OPERATOR |
| `hub:stand:assign` | HUB\_OPERATOR |
| `hub:bag:manage` | HUB\_OPERATOR |
| `hub:manage` | ADMIN |
| `grid:approve` | ADMIN |
| `grid:approve:city` | STATION\_MANAGER |
| `grid:override` | ADMIN |
| `grid:override:city` | STATION\_MANAGER |
| `route:view:assigned` | VAN\_DRIVER |
| `route:stop:confirm` | VAN\_DRIVER |
| `route:approve` | ADMIN |
| `route:approve:city` | STATION\_MANAGER |
| `route:override` | ADMIN |
| `route:override:city` | STATION\_MANAGER |
| `da:queue:view` | DELIVERY\_ASSOCIATE |
| `barcode:attach` | DELIVERY\_ASSOCIATE |
| `scan:event:create` | DELIVERY\_ASSOCIATE, CRON\_DRIVER |
| `cron:run:confirm` | CRON\_DRIVER |
| `manifest:view` | AIRLINE\_GHA |
| `handover:acknowledge` | AIRLINE\_GHA |
| `sla:red:action` | STATION\_MANAGER, SUPERVISOR |
| `exception:escalate` | SUPERVISOR |
| `exception:capture` | CALL\_CENTER\_AGENT |
| `pricing:quote` | B2B\_USER, B2C\_CUSTOMER, C2C\_CUSTOMER |
| `invoice:view:own` | B2B\_USER |
| `api-key:create:own` | B2B\_USER |
| `api-key:manage` | ADMIN |
| `audit:view` | ADMIN |
| `audit:view:city` | STATION\_MANAGER |
| `user:create` | ADMIN |
| `user:create:city` | STATION\_MANAGER |
| `user:deactivate` | ADMIN |
| `user:role:change` | ADMIN |
| `user:role:change:city` | STATION\_MANAGER |
| `config:manage` | ADMIN |
| `flight:manage` | ADMIN |

---

## Per-Role Breakdown

### ADMIN — Global

| Permission | Rationale |
|---|---|
| `user:create` | Onboards all non-B2C staff; B2B requires contract review before account creation |
| `user:deactivate` | Revocation must have global scope — SM cannot remove accounts they didn't create |
| `user:role:change` | Global authority; SM delegation is the narrower `:city` variant |
| `grid:approve` / `grid:override` | Cross-city demand events need platform-level authority |
| `route:approve` / `route:override` | Nightly van routes are locked; intraday changes need senior sign-off |
| `shipment:view` | Full visibility for escalation triage and compliance audits |
| `shipment:override` | Recovers stuck shipments (scan-ledger conflicts, manual state correction) |
| `hub:manage` | Hub infrastructure config (stand layout, dock capacity) — infrequent, high-impact |
| `flight:manage` | Airline schedule sync; errors here break the entire inter-city leg |
| `config:manage` | Platform-wide parameters (SLA thresholds, pricing coefficients) |
| `audit:view` | Unrestricted audit access; SM gets the city-scoped variant only |
| `api-key:manage` | Can revoke any B2B key regardless of owner — needed for security incidents |

Not granted: `shipment:create`, `pricing:quote` (ADMIN is not a customer); `hub:scan`, `da:queue:view` (not an operator); `sla:red:action` (operational role — ADMIN acts via override, not the SLA workflow).

---

### STATION\_MANAGER — City-scoped

Delegated ADMIN authority within one city. Hard limits: cannot modify peer SMs, cannot grant ADMIN.

| Permission | Rationale |
|---|---|
| `user:create:city` | Creates city staff accounts without an ADMIN bottleneck; cannot create ADMIN or peer SM |
| `user:role:change:city` | Changes roles for city staff; SM↔SM and SM→ADMIN blocked |
| `grid:approve:city` / `grid:override:city` | Approves intraday grid rebalancing in their city |
| `route:approve:city` / `route:override:city` | Approves DA route deviations (traffic, breakdown) in their city |
| `shipment:view:city` | Full city shipment visibility needed for SLA oversight |
| `sla:red:action` | First responder for RED SLA legs before escalating to ADMIN |
| `audit:view:city` | City-scoped audit access for compliance and incident review |

Not granted: `user:create` (global create is ADMIN only; SM's variant is scoped to their city); `shipment:override` (stuck-state recovery is ADMIN authority); `config:manage` (platform params are global); `audit:view` (cannot see other cities).

---

### SUPERVISOR — City-scoped

Watches live operations and escalates. Read-heavy; no write authority over shipments or users.

| Permission | Rationale |
|---|---|
| `shipment:view` | Full city shipment visibility to detect SLA drift early |
| `sla:red:action` | Core job: act on RED legs (reassign, escalate, notify) |
| `exception:escalate` | Pushes exceptions to SM/ADMIN; cannot *capture* new ones (that belongs to CC) |

Not granted: `shipment:override` (escalates to SM/ADMIN instead of acting directly); `user:role:change:city` (observer, not a manager); `exception:capture` (origination is CC's responsibility).

---

### HUB\_OPERATOR — City-scoped

Handles physical inbound/outbound operations at the hub. No DA, routing, or config authority.

| Permission | Rationale |
|---|---|
| `hub:scan` | Core job: scans parcels at inbound dock and sortation |
| `hub:stand:assign` | Assigns sorted bags to departure stands |
| `hub:bag:manage` | Creates and seals bags for outbound flight/van legs |
| `shipment:view` | Needs shipment metadata lookups during scanning and bagging |

Not granted: `hub:manage` (stand/dock *configuration* is ADMIN territory; operators use the hub, they don't configure it); `barcode:attach` (DA responsibility before the parcel reaches the hub).

---

### DELIVERY\_ASSOCIATE — City-scoped

Last-mile pickup and delivery. Sees only their assigned work queue.

| Permission | Rationale |
|---|---|
| `da:queue:view` | Views their own M5 dispatch queue |
| `barcode:attach` | Attaches platform barcode to customer parcel at pickup |
| `scan:event:create` | Logs pickup, cron-handoff, and delivery-attempt scan events |
| `shipment:view:assigned` | Can look up only their assigned shipments — not city-wide |

Not granted: `shipment:view` (full view) — restricting to `:assigned` limits data exposure across customer parcels. No hub, routing, or SLA permissions.

---

### VAN\_DRIVER — City-scoped

Executes the nightly planned hub-stop route (M6). No dispatch or parcel-scan authority.

| Permission | Rationale |
|---|---|
| `route:view:assigned` | Views their planned stop sequence for the shift |
| `route:stop:confirm` | Marks each stop complete, triggering next-leg events in M6 |
| `shipment:view:assigned` | Looks up shipment details at a stop |

Not granted: `scan:event:create` (van drivers confirm route stops, not individual parcel scans — that's hub/DA); `da:queue:view` (VAN uses M6 routes, DA uses the M5 priority queue — different systems).

---

### CRON\_DRIVER — City-scoped (origin city)

Runs grid edges exchanging parcels between DAs and hub, and drives the airport trunk leg. Scoped to origin city even though the job physically crosses city boundaries.

| Permission | Rationale |
|---|---|
| `cron:run:confirm` | Confirms the run is feasible before M5 commits the assignment — hard constraint |
| `scan:event:create` | Logs DA handoff receipts on grid edges, hub dock check-in/out, and airport handover/receipt events |
| `shipment:view:assigned` | Looks up parcel details during DA handoffs and hub dock check-in |

Not granted: `route:view:assigned` (CRON follows grid edges driven by M5, not van routes from M6 — separate systems); `barcode:attach` (barcode is attached by DA at customer pickup before the parcel ever reaches CRON).

---

### CALL\_CENTER\_AGENT — City-scoped

Handles inbound customer complaints. Narrow write access; no operational or user-management authority.

| Permission | Rationale |
|---|---|
| `exception:capture` | Creates exception records when a customer reports a failure |
| `shipment:reschedule` | Books a new delivery attempt after a failed delivery |
| `shipment:view` | City-wide shipment lookup needed to handle customer queries |

Not granted: `exception:escalate` (escalation is SUPERVISOR authority — CC captures, SUP escalates); `shipment:override` (cannot modify shipment state directly); `user:create` / `user:role:change` (no user management).

---

### B2B\_USER — Global

Commercial client system. API-driven. Enough access to create, track, and bill shipments — nothing internal.

| Permission | Rationale |
|---|---|
| `shipment:create` | Core product: submitting parcels for intercity delivery |
| `shipment:view:own` | Queries status of their own shipments; not other clients' |
| `pricing:quote` | Computes price before committing to a shipment |
| `invoice:view:own` | Downloads own invoices for reconciliation |
| `api-key:create:own` | Self-service key rotation (capped at 10 keys) |

Not granted: `shipment:view` (full view) — B2B account isolation is a data-security requirement. No operational permissions (hub, DA, SLA).

---

### B2C\_CUSTOMER — Global

End consumer. Book and track only.

| Permission | Rationale |
|---|---|
| `shipment:create` | Books a parcel for delivery |
| `shipment:track:own` | Public-facing tracking for their own parcels |
| `pricing:quote` | Gets a price estimate before booking |

Not granted: `shipment:view:own` (B2C gets the narrower `:track:own` — status only, not full shipment detail); `invoice:view:own` (B2C billing out of scope for v1); `api-key:create:own` (no machine-integration use case for consumers).

---

### C2C\_CUSTOMER — Global

Individual sender shipping to another individual (marketplace seller, personal parcel). Self-registration like B2C. Needs full shipment detail on their own parcels — more active sender than a casual B2C customer.

| Permission | Rationale |
|---|---|
| `shipment:create` | Core use case: sending parcels to other individuals |
| `shipment:view:own` | Full detail on own shipments (address, weight, status) — senders need more than just tracking status |
| `shipment:track:own` | Delivery status and location tracking |
| `pricing:quote` | Estimates cost before booking |

Not granted: `invoice:view:own` (no formal invoicing for individual senders in v1 — receipts handled separately); `api-key:create:own` (no machine-integration use case; C2C is always human-driven); `shipment:reschedule` (rescheduling goes through CC agent, not self-service).

---

### AIRLINE\_GHA — Global (city-agnostic)

Ground handling agent at airports. Two permissions only.

| Permission | Rationale |
|---|---|
| `manifest:view` | Verifies the bag manifest before accepting shipments at the airport |
| `handover:acknowledge` | Signs off on the physical handover, triggering the M9 flight leg |

Not granted: everything else. GHAs have no authority over shipment state, users, or hub ops. The city-scope check is skipped for this role — a GHA may handle flights connecting any two hubs (see `DESIGN.md §AIRLINE_GHA City Scope`).
