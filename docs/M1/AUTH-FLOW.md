# M1 — Authentication & Authorization Flow

This document explains how every request into the system is verified (authentication) and what it is allowed to do (authorization). It covers both the conceptual model and the exact code path.

---

## Part 1 — The Big Picture (Non-Technical)

### Authentication: "Who are you?"

Think of authentication like a security gate at an airport. Before you can go anywhere inside the terminal, you must show an ID. The gate doesn't care where you're going yet — it just needs to confirm you are who you say you are.

In this system there are two valid "IDs" you can show:

| Type | What it looks like | Analogy |
|------|--------------------|---------|
| **JWT (JSON Web Token)** | A signed string passed in every request header | A boarding pass — issued at login, valid for a fixed time, carries your identity |
| **API Key** | A long secret string issued once | A staff access card — doesn't expire by time, but can be revoked; used by B2B systems calling our API programmatically |

If you show neither, or show a fake/expired one, you are turned away at the gate with a **401 Unauthorized**.

### Authorization: "What are you allowed to do?"

Once you're past the gate, authorization decides which rooms you can enter. A delivery associate can scan parcels but cannot create new users. A station manager can manage their own city's staff but cannot touch another city. Only an admin can configure roles and permissions.

This is enforced through **roles** (a named bundle of permissions) assigned to every user. The system checks your role before every sensitive action.

If you are authenticated but try to do something your role doesn't allow, you are stopped with a **403 Forbidden**.

---

## Part 2 — Authentication In Depth

### 2.1 Login → JWT issuance

```
POST /auth/login  { email, password }
        │
        ▼
AuthController.login()
        │
        ├─ load User by email from DB
        ├─ BCrypt.verify(rawPassword, storedHash)  ← fails → 401
        ├─ check user.isActive()                   ← false  → 401
        │
        └─ JwtService.generateToken(user)
                │
                └─ signs a JWT containing:
                     sub  = user UUID
                     role = role name (e.g. "STATION_MANAGER")
                     city = cityId (nullable)
                   with expiry = now + configured TTL
                        ▼
              { "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." }
```

The client stores this token and attaches it to every subsequent request as:
```
Authorization: Bearer <token>
```

### 2.2 API Key authentication

B2B partners and internal services authenticate with a long-lived API key instead of a JWT. The key is issued once via `POST /auth/api-keys` and shown to the user **only at creation time** — it is never stored in plaintext.

```
API Key lifecycle:

  Issue:  raw key → SHA-256 hash → stored in DB
  Use:    client sends raw key in X-Api-Key header
          filter hashes it → looks up hash in DB → finds user
```

This means even if the database is compromised, the raw keys are not exposed.

### 2.3 The Filter: JwtAuthenticationFilter

Every incoming HTTP request passes through `JwtAuthenticationFilter` before reaching any controller. It runs exactly once per request (`OncePerRequestFilter`).

```
Incoming Request
        │
        ├── X-Api-Key header present?
        │       │
        │       ▼
        │   sha256(rawKey) → DB lookup (active keys only, user JOIN'd)
        │       │
        │       ├── not found or user inactive → skip (no auth set)
        │       └── found + active
        │               → stamp apiKey.lastUsedAt = now()
        │               → setAuthentication(user)   ──┐
        │                                              │
        └── Authorization: Bearer <token> present?     │
                │                                      │
                ▼                                      │
           authService.validateToken(token)            │
                │                                      │
                ├── invalid/expired → skip             │
                └── valid → setAuthentication(user) ───┤
                                                       │
                                    ┌──────────────────┘
                                    ▼
                       SecurityContextHolder.setAuthentication(
                           UsernamePasswordAuthenticationToken {
                               principal = AuthUserDetails(user),
                               authorities = ["ROLE_STATION_MANAGER"]
                           }
                       )
        │
        ▼
  Continue down filter chain → Controller
```

**Why silent failure?** Both `tryAuthenticateWithJwt` and `tryAuthenticateWithApiKey` swallow all exceptions. A bad token simply leaves the `SecurityContext` empty; the request continues and the `anyRequest().authenticated()` rule in `SecurityConfig` rejects it with 401. This avoids leaking information about *why* a token was rejected.

### 2.4 AuthUserDetails — the Security Principal

`AuthUserDetails` is the adapter between the domain `User` entity and Spring Security's `UserDetails` interface. It answers the questions Spring Security needs to know:

| Spring Security asks | `AuthUserDetails` answers |
|----------------------|--------------------------|
| What is the username? | `user.getEmail()` |
| What is the password? | `user.getPasswordHash()` (bcrypt hash) |
| What authorities does this user have? | `["ROLE_" + role.getName()]` |
| Is this user enabled? | `user.isActive()` |

The `ROLE_` prefix is Spring Security's convention — it makes `hasRole("ADMIN")` match the authority `"ROLE_ADMIN"`.

Two extra convenience methods not from the interface:
- `getUserId()` — so controllers can extract the caller's UUID without casting
- `getUser()` — so the full domain object is reachable when needed

---

## Part 3 — Authorization In Depth

### 3.1 Route-level authorization (SecurityConfig)

The first authorization gate is at the HTTP route level. Some routes are public; everything else requires an authenticated principal.

```
Public (no token needed):
  POST /auth/login
  POST /auth/register
  GET  /auth/health
  POST /auth/request-onboarding
  Static files (/, index.html, *.js, *.css)

Everything else → must be authenticated (valid JWT or API key)
```

If an unauthenticated request hits a protected route, the configured `authenticationEntryPoint` returns:
```
HTTP 401 Unauthorized
```

### 3.2 Method-level authorization (@PreAuthorize)

After route-level checks, individual controller methods carry `@PreAuthorize` annotations that check the caller's role:

```java
// Only an ADMIN can create a new role
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<RoleResponse> createRole(...) { ... }

// ADMIN or STATION_MANAGER can register a user
@PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
public ResponseEntity<UserResponse> register(...) { ... }
```

These are evaluated by Spring Security before the method body runs. A mismatch returns **403 Forbidden**.

`@EnableMethodSecurity` in `SecurityConfig` is what activates these annotations globally.

### 3.3 Business-logic authorization (service layer)

Some rules cannot be expressed as a simple role check at the method level — they depend on data. These live inside the service:

| Rule | Where enforced |
|------|---------------|
| Station Manager can only create/modify users in their own city | `UserServiceImpl.register()`, `changeRole()` |
| Station Manager cannot grant the ADMIN role | `UserServiceImpl.changeRole()` |
| Station Manager cannot modify another Station Manager or an Admin | `UserServiceImpl.changeRole()` |
| A city-scoped role (DA, Supervisor, etc.) requires a `cityId` | `UserServiceImpl.register()` |

Example — station manager trying to reassign a user from another city:

```
changeRole(targetUserId, newRoleId, actorId=smUserId)
        │
        ├─ load target user   → cityId = "DEL"
        ├─ load actor (SM)    → cityId = "MUM"
        │
        └─ actor.cityId != target.cityId
               → throw ForbiddenException("can only manage users in your own city")
```

### 3.4 The full authorization stack

```
Request arrives with valid JWT (STATION_MANAGER, city=MUM)
        │
        ▼
SecurityConfig route check
  → /api/users/{id}/role  requires authentication ✓
        │
        ▼
@PreAuthorize("hasAnyRole('ADMIN','STATION_MANAGER')") on controller method ✓
        │
        ▼
UserServiceImpl.changeRole()
  → checks: is target in SM's city? is new role not ADMIN? is target not an SM/Admin?
  → all pass ✓
        │
        ▼
Role updated, audit log written
```

---

## Part 4 — Security Configuration Decisions

| Decision | Rationale |
|----------|-----------|
| **Stateless sessions** (`STATELESS`) | No server-side session; every request is self-contained. Simplifies horizontal scaling — any node can serve any request. |
| **CSRF disabled** | CSRF attacks rely on browsers sending cookies automatically. This API uses `Authorization` headers, not cookies, so there is no CSRF surface. |
| **BCrypt for passwords** | Adaptive cost factor — if hardware gets faster, the work factor can be increased; old hashes remain valid until users log in and get re-hashed. |
| **SHA-256 for API key storage** | One-way hash means a DB dump doesn't expose live keys. Unlike passwords, API keys are high-entropy random strings so no salt is needed. |
| **Silent filter failure** | Not leaking token rejection reason prevents oracle attacks (e.g. attacker probing whether a token is expired vs. invalid vs. belonging to a deactivated user). |
| **`@EnableMethodSecurity`** | Method-level `@PreAuthorize` keeps authorization logic co-located with the endpoint it governs, rather than in a distant config file. |

---

## Part 5 — Component Ownership

```
auth/src/main/java/com/oneday/auth/security/
├── SecurityConfig.java          — global HTTP rules, session policy, filter wiring
├── JwtAuthenticationFilter.java — per-request auth: JWT path + API key path
└── AuthUserDetails.java         — domain User ↔ Spring Security UserDetails bridge

auth/src/main/java/com/oneday/auth/service/
├── JwtService / JwtServiceImpl  — token generation and validation (sign/verify)
└── AuthService / AuthServiceImpl — login logic, token refresh, password change
```
