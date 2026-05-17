# M1 Auth — Code Review Plan

**Branch:** `f-M1-Auth-DB`  
**Module:** `auth/` (M1 — Identity, JWT, RBAC)  
**Total production files:** ~50 Java classes + 9 SQL migrations  
**Total test files:** 10 test classes, 174 tests

This plan splits M1 into 5 logical PRs so that each review has a bounded scope and a clear focus.  
PRs must be merged in order — each builds on the previous.

---

## PR Status Overview

| PR | Title | Files | Status |
|----|-------|-------|--------|
| PR-1 | DB Schema, Seeds & Docs | SQL + docs + poms | `[ ] pending` |
| PR-2 | Domain, Repository & DTOs | JPA entities + repos + DTOs + exceptions | `[ ] pending` |
| PR-3 | Security Infrastructure & JWT | SecurityConfig + filter + JwtService | `[ ] pending` |
| PR-4 | Auth Core — Login, API Keys, Password, Permissions | AuthService + AuthController + PermissionService + tests | `[ ] pending` |
| PR-5 | User & Role Management | UserService + RoleService + controllers + remaining tests | `[ ] pending` |

---

## PR-1 — DB Schema, Seeds & Docs

**Commit state:** Already committed on this branch. Ready to push.

### Files

**Flyway migrations** (`app/src/main/resources/db/migration/`)

| File | What it creates |
|------|-----------------|
| `V1__create_permissions.sql` | `permissions` table |
| `V2__create_roles.sql` | `roles` table |
| `V3__create_role_permissions.sql` | M2M join `role_permissions` |
| `V4__create_users.sql` | `users` table |
| `V5__create_api_keys.sql` | `api_keys` table |
| `V6__create_role_audit_logs.sql` | `role_audit_logs` table |
| `V7__seed_permissions.sql` | 41 permission action strings |
| `V8__seed_roles.sql` | 12 built-in roles |
| `V9__seed_role_permissions.sql` | Role-to-permission mapping (~100 rows) |

**Docs** (`docs/M1/`)
- `DESIGN.md` — full M1 architecture
- `SCENARIOS.md` — 12-role permission matrix
- `ROLE-PERMISSIONS.md` — permission string reference
- `DB_Implementation_Plan.md`
- `OPEN-QUESTIONS.md`

**Build config**
- `auth/pom.xml` — Spring Security, JJWT 0.12.5, validation deps
- `app/pom.xml` — PostgreSQL + Flyway deps
- Root `pom.xml` — module declarations

### Review Focus

- **Schema correctness:** Do column types/lengths match the domain? (e.g., `VARCHAR(255)` for email — consider `VARCHAR(320)` which is RFC max)
- **Indexes:** Do queries in `UserRepository`, `ApiKeyRepository` have corresponding indexes? Check `api_keys.key_hash` (used on every authenticated request).
- **FK constraints:** Are ON DELETE behaviors intentional? (e.g., deactivating a role should not cascade-delete users)
- **Seed data:** Do V9 role-permission mappings match `docs/M1/SCENARIOS.md` exactly? City-scoped flag on roles — are the right 6 roles marked `city_scoped = true`?
- **Migration irreversibility:** V7–V9 are data migrations. Any rollback strategy needed?
- **`action` uniqueness:** V1 creates permissions — is there a UNIQUE constraint on `action`?

---

## PR-2 — Domain, Repository & DTOs

**Commit state:** Untracked. Must be committed before raising PR.  
**Commit command:**
```bash
git add common/src/ auth/src/main/java/com/oneday/auth/domain/ \
        auth/src/main/java/com/oneday/auth/repository/ \
        auth/src/main/java/com/oneday/auth/dto/ \
        auth/src/main/java/com/oneday/auth/exception/
```

### Files

**Common module** (`common/src/main/java/com/oneday/common/`)
- `domain/BaseEntity.java` — shared `@MappedSuperclass` with UUID PK + timestamps

**JPA Entities** (`auth/src/main/java/com/oneday/auth/domain/`)
- `User.java` — email, passwordHash, role FK, cityId, active, mustChangePassword
- `Role.java` — name, displayName, cityScoped, builtin, active + `@ManyToMany` permissions
- `Permission.java` — action string
- `ApiKey.java` — keyHash, label, active, lastUsedAt, user FK
- `RoleAuditLog.java` — actorId, targetUserId, action, previousRole, newRole, cityId, reason

**Repositories** (`auth/src/main/java/com/oneday/auth/repository/`)
- `UserRepository` — `findByEmail`, `findActiveByIdWithRole`, `findByIdWithPermissions`, `existsByEmail`
- `RoleRepository` — `findByName`, `findAllByActiveTrue`
- `ApiKeyRepository` — `findActiveByKeyHashWithUser`, `countByUserIdAndActiveTrue`, `findAllByUserId`
- `PermissionRepository` — `findByActionIn`
- `RoleAuditLogRepository` — `findByTargetUserIdOrderByCreatedAtDesc`

**DTOs** (`auth/src/main/java/com/oneday/auth/dto/`)

| Request | Response |
|---------|----------|
| `LoginRequest` | `LoginResponse` |
| `RegisterRequest` | `UserResponse` |
| `RegisterUserRequest` | `RoleResponse` |
| `ApiKeyCreateRequest` | `ApiKeyResponse`, `ApiKeyCreateResponse` |
| `ChangePasswordRequest` | `PermissionCheckResponse` |
| `ResetPasswordRequest` | `AuditLogResponse` |
| `RoleChangeRequest` | |
| `CreateRoleRequest` | |
| `UpdateProfileRequest` | |

**Exceptions** (`auth/src/main/java/com/oneday/auth/exception/`)
- `BadCredentialsException`, `UserNotFoundException`, `EmailAlreadyExistsException`
- `ForbiddenException`, `RoleNotFoundException`, `RoleInUseException`, `ApiKeyCapExceededException`

### Review Focus

- **`BaseEntity` UUIDs:** Is PK generation strategy correct for PostgreSQL? (`@GeneratedValue(strategy = UUID)` vs `gen_random_uuid()` in SQL)
- **Lazy vs Eager loading:** `Role.permissions` — is it `@ManyToMany(fetch = LAZY)`? Eager loading on a join table of 100 rows is fine, but verify the choice is intentional.
- **`findActiveByIdWithRole`:** Uses a JOIN FETCH to avoid N+1 on `user.role`. Verify the JPQL is correct and there's no accidental cross join.
- **`findByIdWithPermissions`:** Should JOIN FETCH both `role` and `role.permissions` in one query — check it's not two separate queries.
- **`api_keys.key_hash` query:** `findActiveByKeyHashWithUser` runs on every API-key-authenticated request. Confirm the DB index exists (V5 migration) and this is a single-row lookup.
- **DTO validation annotations:** `@NotBlank`, `@Email`, `@Size` on request objects — are minimums/maximums enforced? Password minimum length?
- **`CreateRoleRequest.permissions`:** Validated as `@NotEmpty` — good. But is there a maximum to prevent unbounded lists?
- **Audit log immutability:** `RoleAuditLog` should have no setters for `createdAt` — confirm `BaseEntity` uses `@CreatedDate` and the field is not mutable.

---

## PR-3 — Security Infrastructure & JWT

**Commit state:** Untracked. Must be committed before raising PR.  
**Commit command:**
```bash
git add auth/src/main/java/com/oneday/auth/security/ \
        auth/src/main/java/com/oneday/auth/service/JwtService.java \
        auth/src/main/java/com/oneday/auth/service/impl/JwtServiceImpl.java \
        auth/src/test/java/com/oneday/auth/TestAuthApplication.java \
        auth/src/test/java/com/oneday/auth/service/impl/JwtServiceImplTest.java
```

### Files

**Security** (`auth/src/main/java/com/oneday/auth/security/`)
- `SecurityConfig.java` — filter chain, CSRF-off, STATELESS session, 401 entry point
- `JwtAuthenticationFilter.java` — dual-auth: `X-Api-Key` header or `Bearer` token
- `AuthUserDetails.java` — wraps `User` as Spring Security `UserDetails`

**JWT Service**
- `service/JwtService.java` — interface: `createToken`, `parseToken`, `expiryFor`
- `service/impl/JwtServiceImpl.java` — JJWT 0.12.5, HS256, configurable secret + TTL

**Tests**
- `TestAuthApplication.java` — `@SpringBootApplication` stub required by `@WebMvcTest`
- `JwtServiceImplTest.java` — 14 tests: token creation, claims, expiry, parse valid/tampered/expired

### Review Focus

- **JWT secret management:** Is the secret read from `application.properties`? Is there a default value in code? In production, this must come from a secret manager (Vault, AWS Secrets Manager) — flag if a default is hardcoded.
- **JWT expiry:** What is the TTL? Is it configurable per environment? Short-lived tokens (1h) preferred for a logistics app.
- **`mustChangePassword` claim:** Is it embedded in the JWT? If yes, a forced-password-change state persists in tokens until they expire — verify this is acceptable or if there's a token revocation mechanism.
- **Filter ordering:** `JwtAuthenticationFilter` is placed before `UsernamePasswordAuthenticationFilter`. Verify this is correct and that `SecurityContextHolder` is properly cleared between requests.
- **API key auth side-effect:** `tryAuthenticateWithApiKey` updates `lastUsedAt` in-filter, outside a `@Transactional` service boundary. This is a DB write on every API-key request — consider whether this should be async or batched.
- **Swallowed exceptions:** Both `tryAuthenticateWithJwt` and `tryAuthenticateWithApiKey` catch all exceptions silently. Is this intentional? A `catch (Exception ignored)` hides unexpected errors (DB down, etc.).
- **`sha256Hex` duplication:** This utility exists in both `JwtAuthenticationFilter` and `AuthServiceImpl`. Should be extracted to a shared util.
- **`AuthUserDetails.getAuthorities()`:** Does it return the role as a `GrantedAuthority`? If method-level `@PreAuthorize("hasRole('ADMIN')")` is used later, the authority string must be prefixed with `ROLE_`.

---

## PR-4 — Auth Core: Login, API Keys, Password & Permissions

**Commit state:** Untracked. Must be committed before raising PR.  
**Commit command:**
```bash
git add auth/src/main/java/com/oneday/auth/service/AuthService.java \
        auth/src/main/java/com/oneday/auth/service/impl/AuthServiceImpl.java \
        auth/src/main/java/com/oneday/auth/service/PermissionService.java \
        auth/src/main/java/com/oneday/auth/service/impl/PermissionServiceImpl.java \
        auth/src/main/java/com/oneday/auth/api/AuthController.java \
        auth/src/main/java/com/oneday/auth/api/GlobalExceptionHandler.java \
        auth/src/main/java/com/oneday/auth/api/PermissionController.java \
        auth/src/main/java/com/oneday/auth/DataInitializer.java \
        auth/src/test/java/com/oneday/auth/service/impl/AuthServiceImplTest.java \
        auth/src/test/java/com/oneday/auth/service/impl/PermissionServiceImplTest.java \
        auth/src/test/java/com/oneday/auth/api/AuthControllerTest.java \
        auth/src/test/java/com/oneday/auth/api/PermissionControllerTest.java
```

### Files

**Services**
- `service/AuthService.java` — interface: login, register, validateToken, createApiKey, revokeApiKey, listApiKeys, resetPassword, changePassword
- `service/impl/AuthServiceImpl.java` (223 lines) — full implementation
- `service/PermissionService.java` — interface: `canDo(userId, action, cityId)`
- `service/impl/PermissionServiceImpl.java` (46 lines) — city-scoped RBAC check

**Controllers**
- `api/AuthController.java` — `/auth/login`, `/auth/register`, `/auth/health`, API key CRUD, password endpoints
- `api/PermissionController.java` — `/permissions/check`
- `api/GlobalExceptionHandler.java` — `@RestControllerAdvice` mapping all domain exceptions to RFC 9457 `ProblemDetail`

**Bootstrap**
- `DataInitializer.java` — `ApplicationRunner` that seeds initial data if DB is empty

**Tests** (4 test classes, ~70 tests)
- `AuthServiceImplTest.java` — 26 tests
- `PermissionServiceImplTest.java` — 18 tests
- `AuthControllerTest.java` — 19 tests (`@WebMvcTest`)
- `PermissionControllerTest.java` — 10 tests (`@WebMvcTest`)

### Review Focus

- **`revokeApiKey` authorization:** Admin check fetches the user again (`userRepository.findById`) after already having `apiKey.getUser()`. This is an extra DB query — the role is already available on the loaded user entity if the key was fetched with a JOIN.
- **`validateToken` vs active user check:** `validateToken` uses `findActiveByIdWithRole` (checks active flag). Good — a deactivated user cannot use an existing valid JWT. Verify the same check happens on the API key path in the filter.
- **`register` role lookup:** `B2C_CUSTOMER` role is fetched by name at registration time. This will fail loudly if the seed migrations haven't run. The error message ("B2C_CUSTOMER role not seeded") is dev-friendly — confirm it doesn't leak to end users.
- **`resetPassword` audit log:** Logs `PASSWORD_RESET` action. Does it log who performed the reset (actorId)? Verify the controller passes the authenticated user's ID, not the target's ID.
- **`changePassword` not logging:** Unlike `resetPassword`, `changePassword` (self-service) does not write an audit log entry. Is this intentional?
- **`createApiKey` race condition:** `countByUserIdAndActiveTrue` + `save` is not atomic. Under concurrent requests, a user could exceed the 10-key cap. Consider a DB-level constraint or `SELECT ... FOR UPDATE`.
- **`PermissionServiceImpl.canDo` — city scope edge case:** If `role.isCityScoped()` is true but `cityId` param is null or blank, the city check is skipped and the action is allowed. Is this the intended behavior for city-scoped roles when no city context is passed?
- **`GlobalExceptionHandler`:** Uses Spring 6's `ProblemDetail` (RFC 9457). Good choice. Verify that `MethodArgumentNotValidException` (Bean Validation) also triggers on `@RequestParam` violations — it may throw `ConstraintViolationException` instead, which is unhandled.
- **`DataInitializer`:** Does it guard against re-running on a non-empty DB? If the initializer is not idempotent, restarting the app on a populated DB will fail.

---

## PR-5 — User & Role Management

**Commit state:** Untracked. Must be committed before raising PR.  
**Commit command:**
```bash
git add auth/src/main/java/com/oneday/auth/service/UserService.java \
        auth/src/main/java/com/oneday/auth/service/impl/UserServiceImpl.java \
        auth/src/main/java/com/oneday/auth/service/RoleService.java \
        auth/src/main/java/com/oneday/auth/service/impl/RoleServiceImpl.java \
        auth/src/main/java/com/oneday/auth/api/UserController.java \
        auth/src/main/java/com/oneday/auth/api/RoleController.java \
        auth/src/test/java/com/oneday/auth/service/impl/UserServiceImplTest.java \
        auth/src/test/java/com/oneday/auth/service/impl/RoleServiceImplTest.java \
        auth/src/test/java/com/oneday/auth/api/UserControllerTest.java \
        auth/src/test/java/com/oneday/auth/api/RoleControllerTest.java
```

### Files

**Services**
- `service/UserService.java` — interface: register (admin), changeRole, deactivate, reactivate, updateProfile, getUser, getAuditLog
- `service/impl/UserServiceImpl.java` (192 lines)
- `service/RoleService.java` — interface: createRole, listAllRoles, deactivateRole
- `service/impl/RoleServiceImpl.java` (81 lines)

**Controllers**
- `api/UserController.java` (99 lines) — `/users/**` endpoints
- `api/RoleController.java` (44 lines) — `/roles/**` endpoints

**Tests** (4 test classes, ~80 tests)
- `UserServiceImplTest.java` — 31 tests
- `RoleServiceImplTest.java` — 15 tests
- `UserControllerTest.java` — ~30 tests (`@WebMvcTest`)
- `RoleControllerTest.java` — 12 tests (`@WebMvcTest`)

### Review Focus

- **`changeRole` + city-scope invariant:** When a role is changed to a city-scoped role, is `cityId` required on the user? Does `UserServiceImpl.changeRole` enforce this, or is it possible to have a city-scoped role with `cityId = null`?
- **`deactivateRole` guard:** `RoleServiceImpl.deactivateRole` must check that no active user currently holds the role. Verify `RoleInUseException` is thrown if `userRepository.existsByRoleIdAndActiveTrue(roleId)` — confirm this query exists and is correct.
- **`createRole` permission validation:** `CreateRoleRequest.permissions` is a list of action strings. `RoleServiceImpl` must validate each string against the `permissions` table (or the seeded set). Confirm it throws `ForbiddenException` (not `IllegalArgumentException`) for unknown permission strings, matching `GlobalExceptionHandler`.
- **`UserController` endpoint authorization:** Are user management endpoints (`POST /users`, `DELETE /users/{id}`) restricted to ADMIN at the controller or service layer? If via `@PreAuthorize`, verify the authority string matches `AuthUserDetails.getAuthorities()` format.
- **`getAuditLog` access control:** Can any authenticated user fetch audit logs for any other user? Should be restricted to ADMIN or self.
- **`updateProfile` scope:** Can a user update their own email? If yes, is uniqueness re-validated? Can they change their own role via this endpoint?
- **Reactivate user:** Does `reactivate` re-check city assignment for city-scoped roles? A reactivated DA in MUM should not become a DA in DEL simply because the role was changed while deactivated.
- **`listAllRoles`** — returns only active roles, or all? Verify `RoleRepository.findAllByActiveTrue()` is used and that deactivated roles are not surfaced to callers.

---

## General Cross-Cutting Review Items

These apply across all PRs and should be kept in mind throughout.

| Area | Question |
|------|----------|
| **Secret in config** | Is `jwt.secret` in `application.properties` or environment variable? It must NOT be committed. |
| **Transaction boundaries** | Every service method that writes to multiple tables should be `@Transactional`. Verify none are missing. |
| **N+1 queries** | Any place a collection is loaded (e.g., `role.getPermissions()`) inside a loop is a potential N+1. All are avoidable with JOIN FETCH. |
| **`sha256Hex` duplication** | Appears in both `AuthServiceImpl` and `JwtAuthenticationFilter`. Extract to a shared utility class. |
| **Consistent 404 vs 403** | When a user tries to access a resource they're not allowed to see, do we return 403 (exists but forbidden) or 404 (not found)? Pick one policy. |
| **`ConstraintViolationException` unhandled** | `@RequestParam` violations throw `ConstraintViolationException`, not `MethodArgumentNotValidException`. `GlobalExceptionHandler` handles the latter — add a handler for the former. |
| **Test `@SpringBootApplication` stub** | `TestAuthApplication` is in `src/test/` only. It must not be picked up by the production build. Verify it is not in `src/main/`. |
| **`common/KafkaTopics.java`** | Kafka was dropped from the stack. This file is dead code. Delete it or note it's a placeholder. |

---

## Running Tests Before Each PR

```bash
# Full suite — must pass before any PR
mvn clean install -pl auth

# By test class
mvn test -pl auth -Dtest=AuthServiceImplTest
mvn test -pl auth -Dtest=UserControllerTest

# Controller tests only
mvn test -pl auth -Dtest="*ControllerTest"

# Service tests only
mvn test -pl auth -Dtest="*ServiceImplTest"
```

Expected: **174 tests, 0 failures, 0 errors, BUILD SUCCESS**
