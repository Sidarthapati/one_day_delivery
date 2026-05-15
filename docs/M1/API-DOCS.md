# M1 Auth — API Reference

Base path: all endpoints are served from the `auth` module, assembled into the `app` JAR.

## Authentication

All endpoints except the ones marked **Public** require a valid credential in one of two forms:

| Mechanism | Header |
|-----------|--------|
| JWT (issued on login/register) | `Authorization: Bearer <token>` |
| API Key (issued via `/auth/api-keys`) | `Authorization: ApiKey <raw-key>` |

Stateless — no sessions.

---

## Error format

All errors use [RFC 9457 Problem Detail](https://www.rfc-editor.org/rfc/rfc9457):

```json
{
  "status": 422,
  "detail": "email: must not be blank"
}
```

| HTTP status | Situation |
|-------------|-----------|
| 401 | Missing or invalid credential |
| 403 | Authenticated but not permitted |
| 404 | User or role not found |
| 409 | Email already registered |
| 422 | Validation failure, API key cap exceeded, role still in use |

---

## Auth (`/auth`)

### `GET /auth/health`
**Public**

Returns service liveness.

**Response `200`**
```json
{ "status": "UP" }
```

---

### `POST /auth/login`
**Public**

Exchange email + password for a JWT.

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `email` | string | required, valid email |
| `password` | string | required |

```json
{
  "email": "ops@oneday.in",
  "password": "secret123"
}
```

**Response `200`**
| Field | Type | Notes |
|-------|------|-------|
| `token` | string | JWT |
| `expiresAt` | ISO-8601 instant | token expiry |
| `role` | string | role name |
| `cityId` | string \| null | set for city-scoped roles |
| `mustChangePassword` | boolean | true when admin reset a password |

```json
{
  "token": "eyJ...",
  "expiresAt": "2026-05-16T10:00:00Z",
  "role": "CITY_OPS_MANAGER",
  "cityId": "BOM",
  "mustChangePassword": false
}
```

---

### `POST /auth/register`
**Public**

Self-register a new account. Assigns the default `CUSTOMER` role.

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `email` | string | required, valid email |
| `password` | string | required, min 8 chars |
| `name` | string | required |

```json
{
  "email": "new@example.com",
  "password": "mypassword",
  "name": "Priya Sharma"
}
```

**Response `200`** — same shape as `/auth/login`.

**Errors**
- `409` — email already registered

---

### `POST /auth/api-keys`
**Authenticated**

Create a new API key for the calling user. Maximum 5 active keys per user.

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `label` | string | required, non-blank |

```json
{ "label": "ci-pipeline" }
```

**Response `200`**
| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | key identifier |
| `label` | string | |
| `rawKey` | string | **shown only once** — store immediately |
| `createdAt` | ISO-8601 instant | |

```json
{
  "id": "3fa85f64-...",
  "label": "ci-pipeline",
  "rawKey": "od_live_xxxxxxxxxxxx",
  "createdAt": "2026-05-15T08:00:00Z"
}
```

**Errors**
- `422` — 5-key cap exceeded

---

### `GET /auth/api-keys`
**Authenticated**

List all API keys belonging to the calling user.

**Response `200`** — array of:
| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | |
| `label` | string | |
| `active` | boolean | false after revocation |
| `lastUsedAt` | ISO-8601 instant \| null | null if never used |
| `createdAt` | ISO-8601 instant | |

```json
[
  {
    "id": "3fa85f64-...",
    "label": "ci-pipeline",
    "active": true,
    "lastUsedAt": "2026-05-15T09:30:00Z",
    "createdAt": "2026-05-15T08:00:00Z"
  }
]
```

---

### `DELETE /auth/api-keys/{keyId}`
**Authenticated**

Revoke one of the calling user's API keys. Users can only revoke their own keys.

**Path params**
| Param | Type |
|-------|------|
| `keyId` | UUID |

**Response `204`** — no body

---

## Users (`/users`)

### `POST /users`
**Authenticated · Admin-only**

Create a user and assign them a role (used for staff provisioning — not self-registration).

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `name` | string | required |
| `email` | string | required, valid email |
| `password` | string | required, min 8 chars |
| `role` | string | required — role name |
| `cityId` | string \| null | required for city-scoped roles |

```json
{
  "name": "Ravi Kumar",
  "email": "ravi@oneday.in",
  "password": "temp1234",
  "role": "DELIVERY_AGENT",
  "cityId": "DEL"
}
```

**Response `200`**
| Field | Type |
|-------|------|
| `id` | UUID |
| `email` | string |
| `name` | string |
| `role` | string |
| `cityId` | string \| null |
| `active` | boolean |

```json
{
  "id": "7c9e6679-...",
  "email": "ravi@oneday.in",
  "name": "Ravi Kumar",
  "role": "DELIVERY_AGENT",
  "cityId": "DEL",
  "active": true
}
```

**Errors**
- `409` — email already registered

---

### `GET /users/{id}`
**Authenticated**

Fetch a user by ID.

**Path params**
| Param | Type |
|-------|------|
| `id` | UUID |

**Response `200`** — `UserResponse` (same shape as `POST /users` response)

**Errors**
- `404` — user not found

---

### `PUT /users/{id}/role`
**Authenticated · Admin-only**

Change a user's role. Written to the append-only audit log.

**Path params**
| Param | Type |
|-------|------|
| `id` | UUID |

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `newRoleId` | UUID | required |
| `reason` | string \| null | optional free-text |

```json
{
  "newRoleId": "a1b2c3d4-...",
  "reason": "Promoted to city lead"
}
```

**Response `204`** — no body

---

### `GET /users/{id}/audit-log`
**Authenticated**

Return the append-only role-change history for a user.

**Path params**
| Param | Type |
|-------|------|
| `id` | UUID |

**Response `200`** — array of:
| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | log entry ID |
| `actorId` | UUID | who made the change |
| `targetUserId` | UUID | |
| `action` | string | e.g. `ROLE_CHANGE`, `DEACTIVATE` |
| `previousRole` | string \| null | |
| `newRole` | string \| null | |
| `cityId` | string \| null | |
| `reason` | string \| null | |
| `createdAt` | ISO-8601 instant | |

---

### `DELETE /users/{id}`
**Authenticated · Admin-only**

Soft-deactivate a user (sets `active = false`). Does not delete the record.

**Path params**
| Param | Type |
|-------|------|
| `id` | UUID |

**Response `204`** — no body

---

### `PUT /users/{id}/reactivate`
**Authenticated · Admin-only**

Re-enable a previously deactivated user.

**Path params**
| Param | Type |
|-------|------|
| `id` | UUID |

**Response `204`** — no body

---

### `POST /users/{id}/reset-password`
**Authenticated · Admin-only**

Admin-forced password reset for another user. Sets `mustChangePassword = true` on the target, forcing them to change on next login.

**Path params**
| Param | Type |
|-------|------|
| `id` | UUID |

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `newPassword` | string | required, min 8 chars |

```json
{ "newPassword": "Temp@2026!" }
```

**Response `204`** — no body

---

### `PUT /users/me/password`
**Authenticated**

Self-service password change. Requires the current password.

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `currentPassword` | string | required |
| `newPassword` | string | required, min 8 chars |

```json
{
  "currentPassword": "old-secret",
  "newPassword": "new-secret-123"
}
```

**Response `204`** — no body

**Errors**
- `401` — current password incorrect

---

### `PUT /users/me`
**Authenticated**

Update the calling user's display name.

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `name` | string | required, non-blank |

```json
{ "name": "Priya S. Sharma" }
```

**Response `204`** — no body

---

## Roles (`/roles`)

### `POST /roles`
**Authenticated · Admin-only**

Create a custom role from the fixed permission set.

**Request body**
| Field | Type | Constraints |
|-------|------|-------------|
| `name` | string | required, unique identifier |
| `displayName` | string | required, human-readable label |
| `cityScoped` | boolean | true → role carries a cityId |
| `permissions` | string[] | required, non-empty; must be valid permission codes |

```json
{
  "name": "REGIONAL_AUDITOR",
  "displayName": "Regional Auditor",
  "cityScoped": true,
  "permissions": ["ORDER_READ", "SHIPMENT_READ", "AUDIT_LOG_READ"]
}
```

**Response `200`**
| Field | Type |
|-------|------|
| `id` | UUID |
| `name` | string |
| `displayName` | string |
| `cityScoped` | boolean |
| `builtin` | boolean |
| `active` | boolean |

---

### `GET /roles`
**Authenticated**

List all roles (built-in and custom), including inactive ones.

**Response `200`** — array of `RoleResponse` (same shape as `POST /roles` response)

---

### `DELETE /roles/{id}`
**Authenticated · Admin-only**

Deactivate a custom role. Fails if any active user is currently assigned to it.

**Path params**
| Param | Type |
|-------|------|
| `id` | UUID |

**Response `204`** — no body

**Errors**
- `404` — role not found
- `422` — role still assigned to one or more active users

---

## Permissions (`/permissions`)

### `GET /permissions/check`
**Authenticated · Internal**

Check whether a user may perform an action, optionally scoped to a city. Used by other modules for authorization checks — not meant for direct client calls.

**Query params**
| Param | Type | Required |
|-------|------|----------|
| `userId` | UUID | yes |
| `action` | string | yes — permission code, e.g. `ORDER_CREATE` |
| `cityId` | string | no — required only for city-scoped permissions |

```
GET /permissions/check?userId=7c9e6679-...&action=ORDER_CREATE&cityId=BOM
```

**Response `200`**
| Field | Type | Notes |
|-------|------|-------|
| `allowed` | boolean | |
| `reason` | string | human-readable explanation when `allowed = false` |

```json
{ "allowed": true, "reason": "" }
```
