# M1 — Auth Module Design Document

## Overview

The `auth` module handles identity, credential verification, and access control for every actor in the 1DD platform. It is a dependency of all other modules that need to know *who* is making a request and *what* they are allowed to do.

---

## Role Model

Eleven roles are defined as a single Java enum (`Role`). Each role ships with a fixed, immutable set of allowed action strings. There is no dynamic permission table — permissions are code-level constants.

| Role | Scope | Description |
|---|---|---|
| `ADMIN` | Global | Full platform control, user lifecycle, config |
| `STATION_MANAGER` | City | City-level overrides, city-scoped user role changes |
| `SUPERVISOR` | City | SLA red escalation, shipment visibility |
| `HUB_OPERATOR` | City | Hub scanning, stand assignment, bag management |
| `DELIVERY_ASSOCIATE` | City | DA queue, barcode attachment, scan events |
| `VAN_DRIVER` | City | Assigned route view, stop confirmations |
| `CRON_DRIVER` | City | Cron run confirmation, assigned shipment view |
| `CALL_CENTER_AGENT` | City | Exception capture, shipment rescheduling |
| `B2B_USER` | Global | Shipment creation, quoting, own API keys, invoices |
| `B2C_CUSTOMER` | Global | Shipment creation, own tracking, quoting |
| `AIRLINE_GHA` | Global | Manifest view, handover acknowledgement |

**City-scoped roles** (`STATION_MANAGER`, `SUPERVISOR`, `HUB_OPERATOR`, `DELIVERY_ASSOCIATE`, `VAN_DRIVER`, `CRON_DRIVER`, `CALL_CENTER_AGENT`) require a non-null `city_id` on the `users` row. There will be a permission check service enforces that a city-scoped user cannot exercise permissions in a different city.

### Action Strings

Permissions are coarse-grained strings of the form `resource:verb` or `resource:verb:scope`. Samples:

```
shipment:create          shipment:view            shipment:view:own
shipment:view:assigned   shipment:track:own       shipment:override
hub:scan                 hub:stand:assign         hub:bag:manage
grid:approve             grid:override            grid:approve:city
route:view:assigned      route:stop:confirm       route:override:city
da:queue:view            barcode:attach           scan:event:create
cron:run:confirm         manifest:view            handover:acknowledge
sla:red:action           exception:escalate       exception:capture
pricing:quote            invoice:view:own         api-key:create:own
api-key:manage           audit:view               audit:view:city
user:create              user:deactivate          user:role:change
user:role:change:city    config:manage            flight:manage
```

`Role.can(action)` is an O(1) `Set.contains` check. Other modules call the `/permissions/check` endpoint rather than importing the `Role` enum directly.

### CRON_DRIVER City Assignment

`CRON_DRIVER` is city-scoped and is assigned the **origin city**. Their job physically spans two cities (driving from origin hub to destination hub overnight), but their permissions are scoped to the origin city only. The destination city's hub operators and DAs handle the inbound leg. When M5 calls `/permissions/check` for a cron driver, it must pass the origin `cityId`. Passing the destination `cityId` will correctly return `allowed: false`.

### AIRLINE_GHA City Scope

`AIRLINE_GHA` is not in the city-scoped set (`isCityScoped()` returns false). A GHA's `city_id` is set to the airport city by convention but is never enforced by the permission check. If M9 calls `/permissions/check?userId=ghaId&action=handover:acknowledge&cityId=DEL`, it will pass even if the GHA is stored with `city_id=MUM` — the city check block in `PermissionServiceImpl` is skipped for non-city-scoped roles. This is intentional: GHAs may handle flights connecting any two hubs.

---

## Authentication

Two credential modes are supported on every request. The `JwtAuthenticationFilter` checks for an API key header first, then falls back to a Bearer token. Both paths produce the same `AuthUserDetails` principal in the Spring `SecurityContext`.

### JWT (human actors)

- Algorithm: HMAC-SHA (key derived from `jwt.secret` property)
- Default expiry: 8 hours (configurable via `jwt.expiry-hours`)
- Claims: `sub` (userId UUID), `role`, `cityId`, `name`, `mustChangePassword`, `iat`, `exp`
- Login endpoint validates email + BCrypt password, then issues a token
- Token validation re-fetches the user from the DB on every request to catch deactivation in real time

**No refresh token.** Re-login is the intended UX for human actors. Sessions are short (8 h) by design for a delivery-operations platform where shift-end is a natural logout boundary. For long-running programmatic integrations, use API keys instead of attempting to manage JWT refresh.

**JWT secret rotation** is a hard cutover: rotating `jwt.secret` invalidates all outstanding tokens immediately. Users re-authenticate at their next request. This is acceptable because the maximum blast radius is an 8-hour disruption window and operations already tolerate shift-change re-logins. Key rotation should be planned for a low-traffic maintenance window. Multi-key transition (two valid secrets) is not implemented in v1.

### API Keys (B2B machine clients)

- Raw key: 32 bytes from `SecureRandom`, base64url-encoded (256-bit entropy)
- Stored as: SHA-256 hex digest — the raw key is never persisted
- Lookup: `X-Api-Key` header → SHA-256 hash → DB lookup on `api_keys.key_hash`
- `last_used_at` is updated on each successful authentication
- Revocation is soft (set `active = false`)
- Only `B2B_USER` and `ADMIN` roles can create API keys
- **No expiry in v1.** API keys do not have a built-in TTL. Key hygiene (rotation, revocation of unused keys) is the key owner's responsibility. A `GET /auth/api-keys` listing endpoint (see REST API section) lets users discover and revoke stale keys.
- **10-key cap per user.** A user may hold at most 10 active API keys simultaneously. Attempting to create an 11th returns HTTP 422. Revoking a key frees the slot. This cap is enforced in `AuthService.createApiKey()` by counting active keys for the user before insertion.

### Password Storage

BCrypt with Spring's default cost factor (10). Passwords are encoded on registration and never returned in any response.

### First-Login Password Change

The `users` table carries a `must_change_password` boolean (default `false`). It is set to `true` when a user's password is admin-reset (§ Password Management). The login response includes a `mustChangePassword` field. When `true`, the client must direct the user to `PUT /users/me/password` before accessing any other feature. The server does not block other endpoints — enforcement is client-side in v1. The flag is cleared when the user successfully changes their password.

The bootstrap admin seed (`V2__seed_admin.sql`) has `must_change_password = false` because it ships with a well-known credential. The migration comment instructs operators to delete that row and re-register via API once a real admin is provisioned. In production deployments, the seed admin account must be removed before go-live.

---

## Password Management

### Admin/Manager Password Reset

An ADMIN or city-scoped STATION_MANAGER can reset a user's password when they are locked out or suspect compromise.

```
POST /users/{id}/reset-password
Authorization: Bearer <admin or station_manager token>
{ "newPassword": "..." }
```

Authorization rules mirror role-change scoping:
- `ADMIN` can reset any user's password.
- `STATION_MANAGER` can reset passwords only for users in their own city and cannot reset another STATION_MANAGER's or ADMIN's password.

On success: sets `must_change_password = true` and writes a `PASSWORD_RESET` audit log entry (`action = PASSWORD_RESET`, no role fields populated). Publishes a `PASSWORD_RESET` Kafka event.

### Self-Service Password Change

Any authenticated user can change their own password:

```
PUT /users/me/password
Authorization: Bearer <own token>
{ "currentPassword": "...", "newPassword": "..." }
```

`currentPassword` is verified via BCrypt before accepting the new value. Clears `must_change_password = true` on success. No audit log row is written (self-service; no role change). Publishes a `PASSWORD_CHANGED` Kafka event.

### Self-Service Profile Update

Any authenticated user can update their display name:

```
PUT /users/me
Authorization: Bearer <own token>
{ "name": "New Name" }
```

Email is **not** self-serviceable. Email is the login identifier and changing it would bypass uniqueness and audit controls. Email changes must be handled by ADMIN via deactivate-and-recreate. The audit log for the old email is preserved because `role_audit_logs` references `user_id` (UUID), not email.

---

## B2C Self-Registration

`B2C_CUSTOMER` accounts use a public registration endpoint — admin involvement at scale is operationally impossible.

```
POST /auth/register
(public — no auth required)
{ "email": "...", "password": "...", "name": "..." }
```

Behaviour:
- Assigns `role = B2C_CUSTOMER`, `city_id = null`, `active = true`, `must_change_password = false`.
- Email uniqueness enforced (same check as admin registration).
- Returns a `LoginResponse` (auto-login — token issued immediately on registration).
- Writes a `role_audit_logs` entry with `actor_id = new user's own UUID` (self-registration) and `action = CREATE`.
- Publishes `USER_CREATED` Kafka event.

`B2B_USER` accounts are **not** self-serviceable. B2B onboarding involves a commercial contract and API key provisioning; accounts are created by ADMIN.

---

## Account Lifecycle

### Reactivation

A deactivated account can be reactivated by ADMIN only (Station Managers cannot unilaterally reinstate accounts):

```
PUT /users/{id}/reactivate
Authorization: Bearer <admin token>
{ "reason": "..." }
```

Sets `active = true`. Writes `action = REACTIVATE` to `role_audit_logs` — the previous DEACTIVATE row is preserved. Publishes `USER_REACTIVATED` Kafka event. The user's role and `city_id` are unchanged; they resume with the same permissions they had before deactivation.

### API Keys on Deactivation

When a user is deactivated (`active = false`), their API keys are **implicitly dead** — the filter's API key path loads the owning user and checks `user.isActive()` before setting the SecurityContext. No explicit key revocation is needed. When the account is reactivated, those keys immediately work again (still active in DB). If keys should stay dead after reactivation, the admin must explicitly revoke them via `DELETE /auth/api-keys/{keyId}`.

---

## Role & City Scope Edge Cases

### Station Manager Role-Change Restrictions

`enforceStationManagerCityScope()` enforces the following rules when the actor is a `STATION_MANAGER`:

1. **Own city only.** Target user's current `city_id` must match the SM's `city_id`.
2. **Cannot grant ADMIN.** Prevents self-promotion chains.
3. **Cannot modify another STATION_MANAGER.** If the target's current role is `STATION_MANAGER`, the request is rejected. SMs are peers; only ADMIN can change a SM's role.

Rule 3 is a deliberate guard: without it, a SM could demote a peer SM in the same city (e.g., to cover for a personnel dispute), which requires ADMIN oversight.

### City-Hijacking Prevention

A STATION_MANAGER cannot reassign a user from another city into their own city to gain management rights over them. The city-scope check evaluates the **target user's current `city_id`** in the DB at the time of the request. Since a SM cannot change a user's `city_id` if it doesn't already match theirs, and they cannot change their own `city_id` (self-modification goes through ADMIN), the attack surface is closed. The only entity that can move a user between cities is ADMIN.

---

## Domain Model

### `users`

```
id                   UUID PK
email                VARCHAR(255) UNIQUE NOT NULL
password_hash        VARCHAR(255) NOT NULL
name                 VARCHAR(255) NOT NULL
role                 VARCHAR(50)  NOT NULL   -- Role enum name
city_id              VARCHAR(50)             -- NULL for global roles
active               BOOLEAN NOT NULL DEFAULT TRUE
must_change_password BOOLEAN NOT NULL DEFAULT FALSE
created_at           TIMESTAMP NOT NULL
updated_at           TIMESTAMP NOT NULL
```

Indexes: `email` (login lookup), `role` (admin queries).

### `api_keys`

```
id           UUID PK
key_hash     VARCHAR(255) UNIQUE NOT NULL   -- SHA-256 of raw key
user_id      UUID NOT NULL REFERENCES users(id)
label        VARCHAR(255) NOT NULL
active       BOOLEAN NOT NULL DEFAULT TRUE
last_used_at TIMESTAMP
created_at   TIMESTAMP NOT NULL
updated_at   TIMESTAMP NOT NULL
```

Indexes: `user_id`, `key_hash` (auth hot path).

### `role_audit_logs`

Append-only. No row is ever updated or deleted.

```
id             UUID PK
actor_id       UUID NOT NULL          -- who made the change
target_user_id UUID NOT NULL          -- whose record changed
action         VARCHAR(50) NOT NULL   -- CREATE | GRANT | DEACTIVATE | REACTIVATE | PASSWORD_RESET
previous_role  VARCHAR(50)
new_role       VARCHAR(50)
city_id        VARCHAR(50)
reason         TEXT
created_at     TIMESTAMP NOT NULL
updated_at     TIMESTAMP NOT NULL
```

Indexes: `(target_user_id, created_at DESC)`, `(actor_id, created_at DESC)`.

---

## REST API

All endpoints are under the module's base path. Public endpoints are `/auth/login`, `/auth/health`, and `/auth/register`.

### Auth

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/auth/login` | Public | Email+password → JWT + expiry + role + mustChangePassword |
| `GET` | `/auth/health` | Public | Liveness probe |
| `POST` | `/auth/register` | Public | B2C self-registration → auto-login response |
| `POST` | `/auth/api-keys` | `B2B_USER` or `ADMIN` | Create API key; raw key returned once |
| `GET` | `/auth/api-keys` | Authenticated | List own API keys (metadata only, no raw key) |
| `DELETE` | `/auth/api-keys/{keyId}` | Authenticated | Revoke own key (ADMIN can revoke any) |

**Login request/response:**
```json
// POST /auth/login
{ "email": "da@city.in", "password": "..." }

// 200
{
  "token": "<jwt>",
  "expiresAt": "...",
  "role": "DELIVERY_ASSOCIATE",
  "cityId": "BLR",
  "mustChangePassword": false
}
```

**B2C registration request/response:**
```json
// POST /auth/register
{ "email": "customer@example.com", "password": "...", "name": "Aarav Singh" }

// 200 — same shape as login response
{ "token": "<jwt>", "expiresAt": "...", "role": "B2C_CUSTOMER", "cityId": null, "mustChangePassword": false }
```

**API key creation response** (raw key shown once only):
```json
{ "id": "<uuid>", "label": "my-integration", "rawKey": "<base64url>", "createdAt": "..." }
```

**API key listing response** (no raw key):
```json
[
  { "id": "<uuid>", "label": "my-integration", "active": true, "lastUsedAt": "...", "createdAt": "..." }
]
```

### Users

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/users` | `ADMIN` | Register a new non-B2C user |
| `PUT` | `/users/{id}/role` | `ADMIN` or `STATION_MANAGER` | Change a user's role |
| `GET` | `/users/{id}/audit-log` | `ADMIN` or `STATION_MANAGER` | Full role change history |
| `DELETE` | `/users/{id}` | `ADMIN` | Soft-deactivate (sets `active=false`) |
| `PUT` | `/users/{id}/reactivate` | `ADMIN` | Restore a deactivated account |
| `POST` | `/users/{id}/reset-password` | `ADMIN` or `STATION_MANAGER` | Force-reset password; sets mustChangePassword |
| `PUT` | `/users/me/password` | Authenticated | Change own password (requires current password) |
| `PUT` | `/users/me` | Authenticated | Update own display name |

Station managers can only change roles or reset passwords for users in their own city and cannot act on other Station Managers or Admins.

### Permissions

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/permissions/check` | Authenticated | `?userId=&action=&cityId=` → `{allowed, reason}` |

---

## Inter-Module Authorization

Because auth is a Maven submodule in a **monolith** (single JVM), other modules (M4 orders, M5 dispatch, etc.) can inject `PermissionService` as a Spring bean directly. This avoids an HTTP round-trip and is the preferred path for intra-process calls.

```java
// In M4 OrderService — preferred pattern
@Autowired PermissionService permissionService;

PermissionCheckResponse check = permissionService.canDo(userId, "shipment:create", cityId);
if (!check.allowed()) throw new ForbiddenException(check.reason());
```

The HTTP endpoint `GET /permissions/check` exists for two purposes: external callers (admin tooling, testing) and future extraction if the monolith is split into services. Internal modules must not call it over HTTP when the bean is available.

**Service-to-service identity (future gateway scenario):** If modules are ever split, each service would need an identity credential to call `/permissions/check`. The pattern would be: ADMIN creates a named service-account `User` with role `ADMIN` (or a future `INTERNAL_SERVICE` role) and a long-lived API key. Modules authenticate via `X-Api-Key`. This is not implemented in v1 — document only.

---

## Service Layer

All service interfaces are public; implementations are package-private.

### `AuthService`
- `login(LoginRequest)` — credential check + JWT issuance
- `register(B2CSelfRegistrationRequest)` — public B2C account creation + auto-login
- `validateToken(String)` — JWT parse + live DB user fetch
- `createApiKey(UUID, ApiKeyCreateRequest)` — 10-key cap check, generate + hash + persist
- `revokeApiKey(UUID keyId, UUID requestingUserId)` — ownership check then soft-delete
- `listApiKeys(UUID userId)` — returns metadata list for own keys
- `resetPassword(UUID targetId, String newPassword, UUID actorId)` — admin/SM reset, sets mustChangePassword
- `changePassword(UUID userId, String currentPassword, String newPassword)` — self-service, verifies current

### `UserService`
- `register(RegisterUserRequest, UUID adminId)` — email uniqueness check, city enforcement for city-scoped roles, BCrypt encode, audit log entry
- `changeRole(RoleChangeRequest, UUID adminId)` — station manager city-scope enforcement (including SM-to-SM block), audit log
- `deactivate(UUID userId, UUID adminId)` — soft delete + audit log
- `reactivate(UUID userId, UUID adminId)` — restore + audit log
- `updateProfile(UUID userId, UpdateProfileRequest)` — name update only; email immutable
- `getUser(UUID)` — simple fetch

### `PermissionService`
- `canDo(UUID userId, String action, String cityId)` — active check → role action check → city-scope check → `PermissionCheckResponse`

### `JwtService`
- Stateless helper: `createToken(User)`, `parseToken(String)`, `expiryFor(User)`

---

## Security Filter Chain

```
Request
  └─ JwtAuthenticationFilter (OncePerRequestFilter)
       ├─ X-Api-Key header present?
       │    └─ SHA-256 hash → DB lookup (active key) → load User (active check) → setAuthentication
       └─ Authorization: Bearer <token>?
            └─ jjwt parse (signature + expiry) → DB fetch user (live active check) → setAuthentication
  └─ Spring Security authorization
       ├─ /auth/login, /auth/health, /auth/register → permitAll
       └─ all others → authenticated (+ @PreAuthorize method-level checks)
```

Session policy: `STATELESS`. CSRF disabled (API-only service).

**Fail behaviour on DB outage.** If the database is unreachable during the user lookup in `validateToken()` or the API key path, a `DataAccessException` propagates uncaught through the filter and the request returns HTTP 500. This is **fail-closed**: requests are rejected, not silently allowed through. A degraded auth system is preferable to an open one.

**Brute-force protection** is delegated to infrastructure (API gateway or nginx rate-limiting rules). The application layer does not implement login-attempt counters or account lockout in v1. The deliberate `BadCredentialsException` with a uniform message (same error for unknown email and wrong password) prevents user-enumeration even without rate limiting.

---

## Audit Trail

Every mutating operation writes two records:

1. **DB row** in `role_audit_logs` — permanent, queryable via `GET /users/{id}/audit-log`.
2. **Kafka event** on topic `auth.audit` — consumed by downstream observability/SIEM.

### Kafka Event Types

| `eventType` | Trigger |
|---|---|
| `USER_LOGIN` | Successful login |
| `USER_CREATED` | Any registration (admin-driven or self-service B2C) |
| `ROLE_CHANGED` | Role update by admin or station manager |
| `USER_DEACTIVATED` | Admin soft-deletes a user |
| `USER_REACTIVATED` | Admin restores a user |
| `PASSWORD_RESET` | Admin or SM force-resets a password |
| `PASSWORD_CHANGED` | User changes their own password |
| `API_KEY_CREATED` | B2B user or admin creates an API key |
| `API_KEY_REVOKED` | API key soft-deleted |

All events carry `actorId`, `targetUserId`, `cityId`, and `timestamp`.

**Kafka publish failures** are logged but do not roll back the DB transaction. The DB audit log (`role_audit_logs`) is the authoritative record; Kafka events are a secondary signal for observability. Fire-and-forget is an explicit trade-off: a Kafka outage must not prevent logins or registrations.

---

## Key Design Decisions

**Single-role-per-user.** Each user has exactly one role. No role stacking, no permission overrides. Role changes are full replacements and are always audited.

**No token revocation / blacklist.** JWTs are trusted until expiry (≤ 8 h). Deactivation is enforced at token *validation* time via the live DB fetch in `validateToken`, so a deactivated user cannot use a still-unexpired token. Role changes take effect only after the old token expires — this is a deliberate stateless trade-off. The maximum role-change propagation delay is `jwt.expiry-hours` (default 8 h).

**No refresh tokens.** For long-running machine integrations, API keys are the answer. For human actors, re-login at shift-change is the natural boundary.

**API key entropy floor.** 32 bytes of `SecureRandom` → 256-bit key space. SHA-256 is collision-resistant at this entropy level; no salting is needed because the key space makes precomputation infeasible.

**City scope is a runtime constraint, not a schema constraint.** The DB allows any role + any city_id combination; enforcement is in `UserServiceImpl` (on write) and `PermissionServiceImpl` (on check). This keeps the schema simple and lets ADMIN bypass city scope when needed.

**Audit log is append-only by convention.** No DELETE or UPDATE is issued against `role_audit_logs`. There is no DB trigger enforcing this; the invariant is owned by the service layer. The `action` column captures the full lifecycle: `CREATE → GRANT → DEACTIVATE → REACTIVATE → PASSWORD_RESET`.

**B2C vs B2B registration split.** B2C is self-service (no admin bottleneck at scale). B2B is admin-created because onboarding involves a commercial contract, API key provisioning, and invoice setup — steps that require human review anyway.

**Email is immutable after creation.** Email is the login identifier and the natural audit anchor. Allowing self-service email changes would require re-verification infrastructure (email confirmation flow) that is out of scope for v1. Operators handle email changes via deactivate-and-recreate; the audit trail is preserved because all logs reference `user_id` (UUID), not email.

**Monolith-first permission checks.** Internal modules inject `PermissionService` as a bean. The HTTP endpoint exists for external tooling and future service extraction only. This avoids serialization overhead and network round-trips on the hot shipment-booking path.

**Tech stack:** Java 21, Spring Boot 3.2, Spring Security (stateless), jjwt, BCrypt, Flyway, Kafka.
