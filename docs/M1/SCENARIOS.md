# M1 Auth — User Scenarios

> A narrative walkthrough of every scenario the auth module handles, from the first boot of the system all the way through day-to-day operations. Read this if you want to understand *why* the code is shaped the way it is.
>
> **Related docs:** [DESIGN.md](./DESIGN.md) — technical spec, REST API, domain model | [OPEN-QUESTIONS.md](./OPEN-QUESTIONS.md) — open product decisions

---

## The Cast

| Role | Who they are |
|---|---|
| `ADMIN` | Platform super-user. No city boundary. Controls everything. |
| `STATION_MANAGER` | City-level manager. Can manage users and approve grid/route changes, but only within their assigned city. |
| `SUPERVISOR` | Operational supervisor within a city. Handles SLA red-alerts and escalates exceptions. |
| `HUB_OPERATOR` | Works at a sorting hub — scans parcels, assigns stands, manages bags. |
| `DELIVERY_ASSOCIATE` | The person on a bike doing last-mile pickup or delivery. |
| `VAN_DRIVER` | Drives the intra-city van on a planned route. Confirms stops. |
| `CRON_DRIVER` | Drives the inter-city overnight trunk ("cron") run. Confirms the cron leg. |
| `CALL_CENTER_AGENT` | Handles customer complaints, reschedules deliveries, captures exceptions. |
| `B2B_USER` | A business shipping to other businesses. Admin-onboarded. Books shipments, gets quotes, views invoices, creates API keys for system integrations. |
| `B2C_CUSTOMER` | A business shipping to consumers (e.g. an e-commerce company). Admin-onboarded. Books shipments, gets quotes, tracks own parcels. |
| `C2C_CUSTOMER` | An individual shipping a personal parcel to another individual. Self-registers via public endpoint. Books shipments, views and tracks own parcels, gets quotes. |
| `AIRLINE_GHA` | Ground Handling Agent at an airport. Views manifests and acknowledges handover of cargo. |

---

## Chapter 1 — Day Zero: The System Comes Alive

The database migration runs for the first time. Two SQL scripts execute in order.

`V1__create_auth_tables.sql` creates three tables:

- **`users`** — one row per person. Stores email (unique), a bcrypt password hash, display name, role (an enum string), and an optional `city_id`. The `active` flag is the kill-switch.
- **`api_keys`** — one row per API key. Never stores the raw key — only a SHA-256 hash of it. Tracks `last_used_at` so it can be audited. Linked to the owning user by `user_id`.
- **`role_audit_logs`** — append-only. Every time a user is created, has their role changed, or is deactivated, a row lands here. It records who did it (`actor_id`), who it happened to (`target_user_id`), what changed (`previous_role` → `new_role`), and why (`reason` text).

`V2__seed_admin.sql` inserts the bootstrap admin:

```
email:    admin@oneday.in
password: Admin1234!
role:     ADMIN
city_id:  null   ← ADMIN has no city boundary
```

This is the only user that exists without going through the API. The seed comment says to delete this row and re-register once a real admin is set up. From this point forward, every new user is created through the API, which means every creation is logged.

---

## Chapter 2 — Riya Logs In (The Login Flow)

Riya is the platform's system administrator. She opens a terminal and calls:

```
POST /auth/login
{ "email": "admin@oneday.in", "password": "Admin1234!" }
```

`/auth/login` is one of only two endpoints that does **not** require a token — it's open to the world (the other is `/auth/health`).

**What happens inside `AuthServiceImpl.login()`:**

1. Look up the user by email, filtering only `active = true`. If not found → `BadCredentialsException` (same error as a wrong password — the system deliberately does not hint whether the email exists).
2. Run `passwordEncoder.matches(rawPassword, storedHash)` — bcrypt comparison.
3. If the password is wrong → same `BadCredentialsException`.
4. Call `JwtService.createToken(user)`:
   - Build a JJWT with `subject = userId (UUID)`, plus claims `role`, `cityId`, `name`.
   - Sign it with HMAC-SHA using the secret from `jwt.secret` config.
   - Default expiry is **8 hours** (overridable via `jwt.expiry-hours`).
5. Return `LoginResponse` containing the token, expiry instant, role, and cityId.

Riya gets back:

```json
{
  "token": "eyJhbGci...",
  "expiresAt": "2026-05-09T18:00:00Z",
  "role": "ADMIN",
  "cityId": null
}
```

She stores the token. Every subsequent request she makes will carry it as `Authorization: Bearer eyJhbGci...`.

---

## Chapter 3 — Every Request After Login (The Filter Chain)

Once a user has a token, every protected request flows through `JwtAuthenticationFilter` before it reaches any controller. The filter runs once per request (`OncePerRequestFilter`).

**Decision tree inside the filter:**

```
Incoming HTTP request
│
├─ Header "X-Api-Key" present?
│    └─ YES → authenticateViaApiKey()
│         1. SHA-256 hash the raw key value
│         2. Look up api_keys WHERE key_hash = ? AND active = true
│         3. Load owning user, check active = true
│         4. Stamp last_used_at = now(), save
│         5. Set Spring SecurityContext with that user's roles
│
└─ NO → check "Authorization: Bearer <token>"
      └─ token present?
           └─ YES → authenticateViaJwt()
                1. JwtService.parseToken() — verify signature + expiry
                2. Extract subject (userId UUID) from claims
                3. Load user from DB, check active = true
                   (DB check catches deactivations even before token expires)
                4. Set Spring SecurityContext
           └─ NO → no authentication set, request continues unauthenticated
                   (will hit 403 on any protected endpoint)
```

After the filter, Spring Security's `@PreAuthorize` annotations on controllers do the role check. For example `@PreAuthorize("hasRole('ADMIN')")` checks that the authenticated principal has `ROLE_ADMIN` in their granted authorities.

**Key design point:** The filter always calls `filterChain.doFilter()` even on failure. It does not short-circuit with a 401. It simply leaves the SecurityContext empty, and the framework returns 403 when the endpoint requires authentication.

---

## Chapter 4 — Riya Creates a New City Manager (User Registration)

One-Day Delivery is launching in Mumbai. Riya needs to create a Station Manager for the Mumbai hub.

```
POST /users
Authorization: Bearer <riya's token>

{
  "email": "arjun.sharma@oneday.in",
  "password": "Secure#9012",
  "name": "Arjun Sharma",
  "role": "STATION_MANAGER",
  "cityId": "MUM"
}
```

`@PreAuthorize("hasRole('ADMIN')")` — only ADMIN can reach this endpoint.

**Inside `UserServiceImpl.register()`:**

1. Check email uniqueness — throws `IllegalArgumentException` if already registered.
2. Check the city-scoped rule: `STATION_MANAGER` is a city-scoped role (along with SUPERVISOR, HUB_OPERATOR, DELIVERY_ASSOCIATE, VAN_DRIVER, CRON_DRIVER, CALL_CENTER_AGENT). For all city-scoped roles, `cityId` **must** be present and non-blank. Missing it throws immediately.
3. BCrypt-hash the password — raw password never touches the database.
4. Save the `User` row with `active = true`.
5. Write a `role_audit_log` row: `actor_id = Riya's UUID`, `target_user_id = Arjun's UUID`, `action = CREATE`, `new_role = STATION_MANAGER`, `city_id = MUM`, `reason = "User registration"`.
6. Return the created `User` object (password hash is `@JsonIgnore` — never returned in API responses).

Arjun can now log in. His token will carry `role = STATION_MANAGER` and `cityId = MUM`.

---

## Chapter 5 — Arjun Onboards His Field Staff (City-Scoped Registration)

Arjun needs to register a Delivery Associate named Priya for Mumbai. He cannot call `POST /users` himself — that's ADMIN only. Riya does it for him, creating:

```json
{ "role": "DELIVERY_ASSOCIATE", "cityId": "MUM", ... }
```

Now Priya exists. Her JWT will carry `role = DELIVERY_ASSOCIATE`, `cityId = MUM`.

When Priya logs in and calls any endpoint, the filter loads her from DB and sets her authorities as `[ROLE_DELIVERY_ASSOCIATE]`. The actions she's allowed to take are:

```
da:queue:view
barcode:attach
scan:event:create
shipment:view:assigned
```

She cannot view all shipments — only `shipment:view:assigned`. She cannot approve grid changes. She cannot see other cities' data.

### The `isCityScoped` Rule

`isCityScoped` answers one question: does this role have a city boundary?

| `isCityScoped = true` | `isCityScoped = false` |
|---|---|
| `STATION_MANAGER` | `B2B_USER` |
| `SUPERVISOR` | `B2C_CUSTOMER` |
| `HUB_OPERATOR` | `C2C_CUSTOMER` |
| `DELIVERY_ASSOCIATE` | `AIRLINE_GHA` |
| `VAN_DRIVER` | |
| `CRON_DRIVER` | |
| `CALL_CENTER_AGENT` | |

Why it matters in the permission check:

```java
if (isCityScoped(user.role) && cityId != null) {
    if (!user.cityId.equals(cityId)) {
        return { allowed: false, reason: "User scoped to MUM, not DEL" };
    }
}
```

- If the role is city-scoped → the city check runs → Priya (MUM) cannot touch DEL data.
- If the role is **not** city-scoped → the city check is skipped → Riya (ADMIN) can touch any city.

**Concrete contrast** — `canDo("shipment:view", "DEL")`:

| User | Role | isCityScoped | cityId check | Result |
|---|---|---|---|---|
| Priya | `DELIVERY_ASSOCIATE` (MUM) | `true` | MUM ≠ DEL → mismatch | `false` |
| Riya | `ADMIN` | `false` | skipped | `true` |

---

## Chapter 6 — Arjun Promotes a Field Supervisor (Role Change)

After a week, Priya has proven herself. Arjun wants to promote her to SUPERVISOR. He calls:

```
PUT /users/{priya-uuid}/role
Authorization: Bearer <arjun's token>

{
  "newRole": "SUPERVISOR",
  "cityId": "MUM",
  "reason": "Strong performance, promoting to field supervisor"
}
```

`@PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")` — both roles can reach this.

**Inside `UserServiceImpl.changeRole()`:**

1. Load the admin (Arjun) and target (Priya) users.
2. Because Arjun is a `STATION_MANAGER`, `enforceStationManagerCityScope()` runs:
   - Arjun **must** have a cityId.
   - Priya's current cityId **must** match Arjun's cityId. If Priya were in DEL, Arjun couldn't touch her.
   - The `newRole` **must not** be `ADMIN`. A Station Manager can never self-promote or promote others to god-mode.
3. Update Priya's role to `SUPERVISOR` and set her cityId (can be changed as part of promotion — e.g., transferring to a different city, though only ADMIN can cross city boundaries).
4. Write `role_audit_log`: `action = GRANT`, `previous_role = DELIVERY_ASSOCIATE`, `new_role = SUPERVISOR`, with Arjun's reason text.

**Important:** Priya's existing JWT still says `DELIVERY_ASSOCIATE` until it expires (up to 8 hours). After that, she logs in again and gets a fresh token with `SUPERVISOR`. This is a deliberate trade-off — stateless tokens mean no real-time revocation for role changes. Deactivation (Chapter 9) does work immediately because the filter re-checks `active` from the database on every request.

---

## Chapter 7 — Farhan Books a Shipment as a B2B Customer (B2B Onboarding + API Key)

Farhan runs a small e-commerce business (FarhanKart) and signs up for One-Day Delivery. Riya creates his account:

```json
{ "role": "B2B_USER", "cityId": null, ... }
```

`B2B_USER` is **not** city-scoped (`isCityScoped()` returns false). His `cityId` is null — he can book from any city.

His permissions:
```
shipment:create
shipment:view:own
pricing:quote
invoice:view:own
api-key:create:own
```

### Scenario 7a — Farhan Logs In Manually

Farhan logs in via `POST /auth/login` and gets a JWT. He uses it for interactive API calls.

### Scenario 7b — FarhanKart's Backend Needs Programmatic Access

FarhanKart's order management system needs to call the One-Day API automatically every time an order ships — no human in the loop. Farhan creates an API key:

```
POST /auth/api-keys
Authorization: Bearer <farhan's token>

{ "label": "farhankart-oms-prod" }
```

`@PreAuthorize("hasAnyRole('B2B_USER', 'ADMIN')")` — only B2B users and admins can create keys.

**Inside `AuthServiceImpl.createApiKey()`:**

1. Verify the user is active.
2. Generate a 32-byte cryptographically random value using `SecureRandom`, Base64-URL-encode it → this is the `rawKey`.
3. Compute `SHA-256(rawKey)` → this is the `keyHash` stored in DB.
4. Save the `ApiKey` row with `active = true` and `label = "farhankart-oms-prod"`.
5. Return `ApiKeyResponse` containing the **raw key** — this is the **only time** the raw value is ever shown. Once this response is gone, it cannot be recovered. Farhan must save it.

```json
{
  "id": "uuid",
  "label": "farhankart-oms-prod",
  "rawKey": "abc123xyz...",
  "createdAt": "2026-05-09T10:00:00Z"
}
```

FarhanKart's backend now sends every request with `X-Api-Key: abc123xyz...`. The filter hashes it and looks it up. Farhan's JWT is not involved. Farhan can have multiple keys — one per integration system.

### Scenario 7c — Farhan Revokes a Compromised Key

FarhanKart's test API key gets committed to a public GitHub repo. Farhan revokes it:

```
DELETE /auth/api-keys/{keyId}
Authorization: Bearer <farhan's token>
```

`@PreAuthorize("isAuthenticated()")` — any logged-in user can call this, but the service enforces ownership:

- If `apiKey.userId == farhan's userId` → allowed immediately.
- If not → check if the requester has `api-key:manage` permission (only `ADMIN` has this). Otherwise → `SecurityException`.

The key's `active` flag is set to `false`. Next time FarhanKart sends it, the filter finds no active key with that hash → authentication fails → 403.

---

## Chapter 8 — Meera Ships Her Old Phone to a Friend (C2C Self-Registration)

Meera wants to send her old phone to a friend in Delhi. She's an individual — no business account, no contract. She opens the app and signs up directly:

```
POST /auth/register
(no Authorization header — public endpoint)

{
  "email": "meera@gmail.com",
  "password": "Meera@1234",
  "name": "Meera Pillai"
}
```

**Inside `AuthServiceImpl.register()`:**

1. `UserRepository.existsByEmail("meera@gmail.com")` — returns false. Good.
2. `RoleRepository.findByName("C2C_CUSTOMER")` — fetches the seeded role. No caller input on role: the endpoint hardcodes it. Meera cannot sign up as B2B or ADMIN.
3. BCrypt-hash her password. `city_id = null` — C2C customers are not city-scoped; they can ship from any city.
4. `UserRepository.save(user)` — row inserted.
5. `RoleAuditLogRepository.save(log)` — `actor_id = meera's UUID`, `action = CREATE`, `new_role = C2C_CUSTOMER`. Self-registration means actor and target are the same person.
6. JWT issued immediately — no separate login step needed.

Meera gets back:
```json
{
  "token": "eyJhbGci...",
  "expiresAt": "2026-05-16T22:00:00Z",
  "role": "C2C_CUSTOMER",
  "cityId": null,
  "mustChangePassword": false
}
```

Her permissions:
```
shipment:create
shipment:view:own
shipment:track:own
pricing:quote
```

She can get a quote, book a shipment, and track it. She cannot view other people's shipments, cannot access invoices, cannot create API keys.

**Why C2C gets self-registration but B2C and B2B don't:**

Meera is an individual — there are no commercial pre-conditions to check before she can use the service. Forcing her through an admin creates unnecessary friction and loses the customer. B2C and B2B accounts represent *businesses* — they need a service agreement, negotiated rates, and (for B2B) invoice and API key setup. Those steps require human review, so admin-created onboarding is the right gate.

---

## Chapter 9 — The GHA at Mumbai Airport (AIRLINE_GHA Role)

IndigoAir's Ground Handling Agent at Mumbai airport uses One-Day Delivery's system to acknowledge cargo handover. Riya creates their account:

```json
{ "role": "AIRLINE_GHA", "cityId": "MUM", ... }
```

Wait — but `isCityScoped()` does **not** include `AIRLINE_GHA`. So `cityId` is optional for them. In practice it's set to the airport city for operational clarity, but the system doesn't enforce it.

Their permissions:
```
manifest:view
handover:acknowledge
```

They cannot book shipments, cannot see pricing, cannot touch any user management. They log in, view the cargo manifest for their flight, and acknowledge handover. That's the entire surface area of their role.

---

## Chapter 10 — A Rogue Employee Gets Deactivated (Account Deactivation)

A hub operator at the Delhi hub has been flagged for fraudulent scan events. Riya deactivates them immediately:

```
DELETE /users/{employee-uuid}
Authorization: Bearer <riya's token>
```

`@PreAuthorize("hasRole('ADMIN')")` — only ADMIN can deactivate.

**Inside `UserServiceImpl.deactivate()`:**

1. Load the user.
2. Set `active = false` and save.
3. Write `role_audit_log`: `action = DEACTIVATE`, `previous_role = HUB_OPERATOR`, `reason = "Account deactivated by admin"`.

**This takes effect on the next request**, not the current one. Here's why: the JWT filter, on every request, loads the user from DB and checks `user.isActive()`. Even if the employee has a valid, non-expired token, the filter will find `active = false` and fail to set the SecurityContext. Their very next request returns 403.

The token is not explicitly revoked (there's no token blocklist). It simply becomes useless because the DB lookup fails.

---

## Chapter 11 — Other Modules Ask "Can This User Do That?" (Permission Check)

The `orders` module (M4) wants to verify that a caller has `shipment:create` permission before booking a shipment. Because the monolith runs in a single JVM, M4 injects `PermissionService` as a Spring bean — no HTTP round-trip:

```java
PermissionCheckResponse check = permissionService.canDo(userId, "shipment:create", cityId);
if (!check.allowed()) throw new ForbiddenException(check.reason());
```

The HTTP endpoint `GET /permissions/check` exists for external callers (admin tooling, integration testing) only. Internal modules must use the bean directly.

**Inside `PermissionServiceImpl.canDo()`:**

1. Load the user by UUID.
2. If `active = false` → `{ "allowed": false, "reason": "User account is inactive" }`.
3. Check `user.getRole().can(action)` — does the role's `allowedActions` set contain the action string?
   - If no → `{ "allowed": false, "reason": "Role X does not permit action: shipment:create" }`.
4. If `cityId` is provided in the request **and** the user's role is city-scoped:
   - The user's `cityId` must match the requested `cityId`. A Mumbai DA cannot create a shipment attributed to Delhi.
   - If mismatch → `{ "allowed": false, "reason": "User is scoped to city MUM, not DEL" }`.
5. All checks pass → `{ "allowed": true, "reason": "Allowed" }`.

This is the cross-module authorization contract. Every module that needs to gate an action by role calls this endpoint. The auth module is the single source of truth.

### Example — Priya Views Her Queue (PASS)

Priya's token carries:

```
userId:  priya-uuid
role:    DELIVERY_ASSOCIATE
cityId:  MUM
```

`DELIVERY_ASSOCIATE` has these actions:
```
da:queue:view
barcode:attach
scan:event:create
shipment:view:assigned
```

M5 (dispatch) receives `GET /da/queue` with Priya's token and calls:

```
permissionService.canDo(priya-uuid, "da:queue:view", "MUM")

Step 1 — active check:
   user.active = true ✅

Step 2 — role action check:
   DELIVERY_ASSOCIATE.can("da:queue:view") → true ✅

Step 3 — city check:
   isCityScoped(DELIVERY_ASSOCIATE) = true
   cityId param = "MUM", user.cityId = "MUM" → match ✅

→ { allowed: true, reason: "Allowed" }
```

### Example — Hub Operator Tries to Book a Shipment (FAIL)

A Hub Operator calls a booking endpoint. M4 (orders) checks:

```
permissionService.canDo(hubOp-uuid, "shipment:create", "DEL")

Step 2 — role action check:
   HUB_OPERATOR.can("shipment:create") → false ✗

→ { allowed: false, reason: "Role HUB_OPERATOR does not permit action: shipment:create" }
```

Booking is a `B2B_USER` action. A Hub Operator's job is scanning and stand assignment — they have no business creating shipments. M4 rejects with 403.

---

## Chapter 12 — Viewing the Audit Trail

Riya or Arjun (within their city) can view the full history of role changes for any user:

```
GET /users/{uuid}/audit-log
Authorization: Bearer <admin or station_manager token>
```

Returns all `role_audit_logs` rows for that user, newest first. Each row shows:

- `actorId` — who made the change
- `action` — CREATE / GRANT / DEACTIVATE
- `previousRole` / `newRole` — what changed
- `cityId` — city context at the time
- `reason` — the human-written justification
- `createdAt` — when it happened

This log is **append-only** — there is no DELETE or UPDATE path on `role_audit_logs` anywhere in the codebase. It is a permanent record.

---

## Summary: The Full Auth Lifecycle

```
Day 0
  └─ DB migration runs → users + api_keys + role_audit_logs tables created
  └─ Seed admin inserted (admin@oneday.in)

C2C Self-Registration (public)
  └─ POST /auth/register → hardcoded role = C2C_CUSTOMER
  └─ Email uniqueness check, BCrypt hash, audit log written
  └─ JWT issued immediately (auto-login)

Onboarding (Admin only — for B2C, B2B, and all staff roles)
  └─ POST /users → register user with role + cityId
  └─ Role validation: city-scoped roles require cityId
  └─ Password bcrypt-hashed, audit log written

Login
  └─ POST /auth/login (open endpoint)
  └─ Email lookup (active=true only) → bcrypt match
  └─ JWT issued: sub=userId, claims={role,cityId,name}, signed HMAC-SHA, 8h TTL

Every Request
  └─ JwtAuthenticationFilter runs first
       ├─ X-Api-Key header → SHA-256 hash → DB lookup → user loaded
       └─ Authorization: Bearer → JWT parsed → signature + expiry verified → user loaded from DB (active check)
  └─ Spring Security @PreAuthorize does role check on controllers

Permission Checks (cross-module)
  └─ GET /permissions/check?userId&action&cityId
  └─ Role.can(action) + city-scope enforcement
  └─ Returns { allowed, reason }

API Keys (B2B integrations)
  └─ POST /auth/api-keys → raw key returned once, hash stored
  └─ DELETE /auth/api-keys/{id} → active=false, stops working immediately

Role Changes
  └─ PUT /users/{id}/role (ADMIN or STATION_MANAGER)
  └─ Station Manager: city-scope enforced, cannot grant ADMIN
  └─ Audit log row written
  └─ Old JWT still valid until expiry (stateless trade-off)

Deactivation
  └─ DELETE /users/{id} (ADMIN only)
  └─ active=false → DB re-check in filter kills next request immediately
  └─ Audit log written

Audit Trail
  └─ GET /users/{id}/audit-log (ADMIN or STATION_MANAGER)
  └─ Append-only role_audit_logs, newest first
```

---

## Chapter 13 — One Day in the Life of the Database  

*A row-level walkthrough. Every action in Chapters 1–12 leaves a trace in the database. This is what that trace looks like.*

---

### 06:00 — Migrations Run, the Schema Exists, Seed Data Lands

The app starts for the first time. Flyway executes V1 through V9 in order.

After V7 completes, the `permissions` table holds 40 rows. A sample:

| id (UUID) | action |
|---|---|
| `a1b2c3...` | `shipment:create` |
| `d4e5f6...` | `shipment:view` |
| `g7h8i9...` | `hub:scan` |
| `j0k1l2...` | `api-key:create:own` |
| … 36 more … | … |

After V8, the `roles` table holds 12 rows:

| id (UUID) | name | display_name | city_scoped | is_builtin | active |
|---|---|---|---|---|---|
| `r1...` | `ADMIN` | Administrator | false | true | true |
| `r2...` | `STATION_MANAGER` | Station Manager | true | true | true |
| `r3...` | `DELIVERY_ASSOCIATE` | Delivery Associate | true | true | true |
| `r4...` | `B2B_USER` | B2B User | false | true | true |
| … 8 more … | … | … | … | … | … |

After V9, the `role_permissions` table holds 68 rows — one per (role, permission) pair. A sample for just `ADMIN`:

| role_id | permission_id |
|---|---|
| `r1...` | `a1b2c3...` ← shipment:create |
| `r1...` | `d4e5f6...` ← shipment:view |
| `r1...` | `g7h8i9...` ← hub:scan |
| … 9 more for ADMIN … | … |

No `users` rows yet. No `api_keys`. No `role_audit_logs`. The platform is an empty stage.

---

### 08:00 — Riya Logs In (No DB Write)

`POST /auth/login { "email": "admin@oneday.in", "password": "Admin1234!" }`

The `users` table is read (one SELECT by email, `active = true`). BCrypt comparison runs in memory. A JWT is issued. Nothing is written — login is a read-only operation on the DB.

---

### 09:15 — Riya Creates Arjun (First Row in `users`, First Row in `role_audit_logs`)

`POST /users { "email": "arjun.sharma@oneday.in", "role": "STATION_MANAGER", "cityId": "MUM" }`

Two DB writes happen:

**`users` table — 1 new row:**

| id | email | password_hash | name | role_id | city_id | active | must_change_password |
|---|---|---|---|---|---|---|---|
| `u-arjun` | arjun.sharma@oneday.in | `$2a$12$Kx...` | Arjun Sharma | `r2...` ← STATION_MANAGER | MUM | true | false |

`role_id` is a FK to `roles.id`. The bcrypt hash is 60 characters; the raw password `"Secure#9012"` is gone.

**`role_audit_logs` table — 1 new row:**

| id | actor_id | target_user_id | action | previous_role | new_role | city_id | reason |
|---|---|---|---|---|---|---|---|
| `log-1` | `u-riya` | `u-arjun` | CREATE | null | STATION_MANAGER | MUM | User registration |

`actor_id` and `target_user_id` are plain UUIDs — no FK to `users`. Even if Riya's account is later deleted, this row survives intact.

---

### 10:00 — Arjun's Staff Get Created (Three More `users` Rows)

Riya registers Priya (DELIVERY_ASSOCIATE / MUM), Rohan (HUB_OPERATOR / MUM), and Kavya (SUPERVISOR / MUM). Three rows land in `users`, three rows land in `role_audit_logs`.

**`users` after all three:**

| id | email | name | role_id (→ roles.name) | city_id | active |
|---|---|---|---|---|---|
| `u-arjun` | arjun.sharma@… | Arjun Sharma | STATION_MANAGER | MUM | true |
| `u-priya` | priya.desai@… | Priya Desai | DELIVERY_ASSOCIATE | MUM | true |
| `u-rohan` | rohan.mehta@… | Rohan Mehta | HUB_OPERATOR | MUM | true |
| `u-kavya` | kavya.nair@… | Kavya Nair | SUPERVISOR | MUM | true |

**`role_audit_logs` — 4 rows so far, all `action = CREATE`.**

---

### 11:30 — Riya Onboards Farhan (B2B User, No `city_id`)

`POST /users { "email": "farhan@farhankart.com", "role": "B2B_USER", "cityId": null }`

**`users` — new row:**

| id | email | name | role_id (→ roles.name) | city_id | active |
|---|---|---|---|---|---|
| `u-farhan` | farhan@farhankart.com | Farhan Khan | B2B_USER | **null** | true |

`B2B_USER` is not city-scoped. `city_id` is null — he can transact across all cities. One more `role_audit_logs` row with `action = CREATE`.

---

### 12:00 — Farhan Creates an API Key (First Row in `api_keys`)

`POST /auth/api-keys { "label": "farhankart-oms-prod" }`

The service generates a 32-byte random value → Base64-URL-encodes it → SHA-256-hashes it.

**`api_keys` table — 1 new row:**

| id | key_hash | user_id | label | active | last_used_at |
|---|---|---|---|---|---|
| `k-farhan-1` | `sha256:e3b0c4...` | `u-farhan` | farhankart-oms-prod | true | null |

The raw key `"AbCdEf12...XyZ"` is returned in the API response and never written anywhere. `last_used_at` is null — the key has not been used yet.

---

### 14:00 — FarhanKart's Backend Makes Its First Call (API Key Stamped)

FarhanKart sends `X-Api-Key: AbCdEf12...XyZ` on a shipment creation request. The filter hashes it, looks up the `api_keys` row, and stamps it.

**`api_keys` row updated:**

| id | key_hash | label | active | **last_used_at** |
|---|---|---|---|---|
| `k-farhan-1` | `sha256:e3b0c4...` | farhankart-oms-prod | true | **2026-05-13 14:00:03** |

This is the only mutable field on an api_key row. Everything else is write-once.

---

### 15:45 — Arjun Promotes Priya (Role Change, New `role_audit_logs` Row)

`PUT /users/u-priya/role { "newRole": "SUPERVISOR", "cityId": "MUM", "reason": "Strong performance" }`

**`users` row updated:**

| id | name | role_id (before) | role_id (after) | city_id |
|---|---|---|---|---|
| `u-priya` | Priya Desai | DELIVERY_ASSOCIATE → | **SUPERVISOR** | MUM |

The `role_id` FK value changes from the DELIVERY_ASSOCIATE UUID to the SUPERVISOR UUID.

**`role_audit_logs` — new row (row 6 total):**

| id | actor_id | target_user_id | action | previous_role | new_role | city_id | reason |
|---|---|---|---|---|---|---|---|
| `log-6` | `u-arjun` | `u-priya` | GRANT | DELIVERY_ASSOCIATE | SUPERVISOR | MUM | Strong performance |

The old row (`log-2`, `action = CREATE`, `new_role = DELIVERY_ASSOCIATE`) is untouched. The audit trail now tells a complete story: Priya was created as a DA, then promoted to SUPERVISOR by Arjun.

---

### 17:00 — The GHA Account Is Created (No `city_id` Enforcement)

`POST /users { "email": "gha.mum@indigo.in", "role": "AIRLINE_GHA", "cityId": "MUM" }`

| id | email | role_id (→ name) | city_id | active |
|---|---|---|---|---|
| `u-gha` | gha.mum@indigo.in | AIRLINE_GHA | MUM | true |

`AIRLINE_GHA` is not in the city-scoped list, so `city_id` was optional — Riya set it anyway for operational clarity. Their `role_permissions` entries cover only `manifest:view` and `handover:acknowledge`.

---

### 18:30 — A Rogue Operator Is Deactivated (Final State)

Rohan is found to have entered fraudulent scan events. Riya deactivates him:

`DELETE /users/u-rohan`

**`users` row updated:**

| id | name | role_id | active (before → after) |
|---|---|---|---|
| `u-rohan` | Rohan Mehta | HUB_OPERATOR | true → **false** |

**`role_audit_logs` — new row:**

| id | actor_id | target_user_id | action | previous_role | new_role | reason |
|---|---|---|---|---|---|---|
| `log-8` | `u-riya` | `u-rohan` | DEACTIVATE | HUB_OPERATOR | null | Account deactivated by admin |

Rohan's next HTTP request — even if his JWT hasn't expired — returns 403. The filter does a DB lookup, finds `active = false`, and refuses to set the SecurityContext.

---

### End of Day — Full Database Snapshot

**`permissions`** — 40 rows (static seed, unchanged)

**`roles`** — 12 rows (static seed, unchanged)

**`role_permissions`** — 68 rows (static seed, unchanged)

**`users`** — 6 rows:

| name | role | city_id | active |
|---|---|---|---|
| Riya (admin seed) | ADMIN | null | true |
| Arjun Sharma | STATION_MANAGER | MUM | true |
| Priya Desai | **SUPERVISOR** | MUM | true |
| Rohan Mehta | HUB_OPERATOR | MUM | **false** |
| Kavya Nair | SUPERVISOR | MUM | true |
| Farhan Khan | B2B_USER | null | true |
| GHA Mumbai | AIRLINE_GHA | MUM | true |

**`api_keys`** — 1 row (Farhan's `farhankart-oms-prod`, `active = true`, `last_used_at` stamped)

**`role_audit_logs`** — 8 rows, in order:

| # | actor | target | action | previous → new |
|---|---|---|---|---|
| 1 | Riya | Arjun | CREATE | — → STATION_MANAGER |
| 2 | Riya | Priya | CREATE | — → DELIVERY_ASSOCIATE |
| 3 | Riya | Rohan | CREATE | — → HUB_OPERATOR |
| 4 | Riya | Kavya | CREATE | — → SUPERVISOR |
| 5 | Riya | Farhan | CREATE | — → B2B_USER |
| 6 | Arjun | Priya | GRANT | DELIVERY_ASSOCIATE → SUPERVISOR |
| 7 | Riya | GHA | CREATE | — → AIRLINE_GHA |
| 8 | Riya | Rohan | DEACTIVATE | HUB_OPERATOR → — |

Every mutation to user identity — creation, promotion, deactivation — has a corresponding, permanent, FK-free row here. The table is append-only. No row in this table has ever been updated or deleted.
