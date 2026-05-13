# M1 — DB Implementation Plan

> **Module:** `auth` | **Status:** Ready to implement | **Updated:** 2026-05-12
>
> Covers everything needed to go from an empty `auth` module to a running schema with seeded data.

---

## Phase 1 — Auth module dependencies (`auth/pom.xml`)

Add two missing dependencies:

| Dependency | Reason |
|---|---|
| `spring-boot-starter-data-jpa` | JPA entities + Spring Data repositories |
| `spring-boot-starter-security` | BCrypt (`PasswordEncoder`), stateless filter chain |

---

## Phase 2 — Flyway migrations (`app/src/main/resources/db/migration/`)

Nine migration files in strict dependency order. Create the directory first — it does not exist yet.

### Schema migrations

| File | Contents |
|---|---|
| `V1__create_permissions.sql` | `permissions` table — no FKs |
| `V2__create_roles.sql` | `roles` table — no FKs |
| `V3__create_role_permissions.sql` | `role_permissions` join table (FK → `roles`, `permissions`) |
| `V4__create_users.sql` | `users` table (FK → `roles`) |
| `V5__create_api_keys.sql` | `api_keys` table (FK → `users`) |
| `V6__create_role_audit_logs.sql` | `role_audit_logs` table — **no FKs** on `actor_id`/`target_user_id` (append-only; audit trail must survive a deleted user) |

### Seed migrations

| File | Contents |
|---|---|
| `V7__seed_permissions.sql` | 39 action strings from `ROLE-PERMISSIONS.md` |
| `V8__seed_roles.sql` | 12 built-in roles with correct `city_scoped` / `is_builtin` flags |
| `V9__seed_role_permissions.sql` | All role → permission rows, cross-referenced from `ROLE-PERMISSIONS.md` |

### Table definitions

#### `permissions`
```sql
CREATE TABLE permissions (
    id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action VARCHAR(100) NOT NULL UNIQUE
);
```

#### `roles`
```sql
CREATE TABLE roles (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    city_scoped  BOOLEAN NOT NULL DEFAULT FALSE,
    is_builtin   BOOLEAN NOT NULL DEFAULT FALSE,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_roles_name ON roles(name);
```

#### `role_permissions`
```sql
CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id),
    permission_id UUID NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);
```

#### `users`
```sql
CREATE TABLE users (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                VARCHAR(255) NOT NULL UNIQUE,
    password_hash        VARCHAR(255) NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    role_id              UUID NOT NULL REFERENCES roles(id),
    city_id              VARCHAR(50),
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email   ON users(email);
CREATE INDEX idx_users_role_id ON users(role_id);
```

#### `api_keys`
```sql
CREATE TABLE api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash     VARCHAR(255) NOT NULL UNIQUE,
    user_id      UUID NOT NULL REFERENCES users(id),
    label        VARCHAR(255) NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_keys_user_id  ON api_keys(user_id);
CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);
```

#### `role_audit_logs`
```sql
CREATE TABLE role_audit_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id       UUID NOT NULL,
    target_user_id UUID NOT NULL,
    action         VARCHAR(50) NOT NULL,
    previous_role  VARCHAR(100),
    new_role       VARCHAR(100),
    city_id        VARCHAR(50),
    reason         TEXT,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_target ON role_audit_logs(target_user_id, created_at DESC);
CREATE INDEX idx_audit_actor  ON role_audit_logs(actor_id, created_at DESC);
```

---

## Phase 3 — Java domain entities (`auth/src/main/java/com/oneday/auth/domain/`)

All entities (except `RoleAuditLog`) extend `BaseEntity` from `common` for `id`, `createdAt`, `updatedAt`.

| Entity | Table | Notes |
|---|---|---|
| `Permission` | `permissions` | `String action` field |
| `Role` | `roles` | `@ManyToMany` to `Permission` via `role_permissions` `@JoinTable` — no separate entity needed (join has no extra columns) |
| `User` | `users` | `@ManyToOne Role role`; `String cityId`; `boolean active`, `mustChangePassword` |
| `ApiKey` | `api_keys` | `@ManyToOne User user`; `String keyHash`, `label`; `Instant lastUsedAt` |
| `RoleAuditLog` | `role_audit_logs` | Append-only; `actor_id` and `target_user_id` stored as plain `UUID` — no FK constraint, so audit survives user deletion |

---

## Phase 4 — Spring Data repositories (`auth/src/main/java/com/oneday/auth/repository/`)

| Repository | Key query methods |
|---|---|
| `UserRepository` | `findByEmail(String)`, `existsByEmail(String)` |
| `RoleRepository` | `findByName(String)`, `findAllByActiveTrue()` |
| `PermissionRepository` | `findByAction(String)`, `findAllByActionIn(Collection<String>)` (batch validation in `RoleService`) |
| `ApiKeyRepository` | `findByKeyHash(String)` (auth hot path), `countByUserIdAndActiveTrue(UUID)` (10-key cap check), `findAllByUserId(UUID)` |
| `RoleAuditLogRepository` | `findByTargetUserIdOrderByCreatedAtDesc(UUID)`, `findByActorIdOrderByCreatedAtDesc(UUID)` |

---

## Execution order

1. **Phase 1** — add JPA/Security dependencies to `auth/pom.xml`
2. **Phase 2** — write schema migrations (V1–V6), then seed migrations (V7–V9)
3. **Phase 3** — write entities (match migration column names exactly)
4. **Phase 4** — write repositories (thin layer, no business logic)

`mvn clean install -pl auth` after each phase to catch wiring issues early.
