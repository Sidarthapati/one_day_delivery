# M1 Auth — Controller → Service → Repository Flow

## Postman Setup

1. Create a new collection: **1DD-M1-Auth**
2. Create an environment **1DD-Local** with these variables:

| Variable | Initial value |
|---|---|
| `base_url` | `http://localhost:8080` |
| `jwt` | *(leave blank — populated by test scripts)* |
| `admin_jwt` | *(leave blank — populated by test scripts)* |
| `user_id` | *(leave blank)* |
| `role_id` | *(leave blank)* |
| `apikey_id` | *(leave blank)* |
| `onboarding_id` | *(leave blank)* |

3. Select **1DD-Local** as the active environment.
4. Seeded admin credentials: `admin@oneday.in` / `Admin1234!`

---

## `/auth` — AuthController

### `GET /auth/health`
```
AuthController.health()
  → Map.of("status", "UP")          [no service/repo call]
```

**Postman**  
Method: `GET` · URL: `{{base_url}}/auth/health` · Auth: none  
Expected: `200` → `{ "status": "UP" }`

---

### `POST /auth/login`
```
AuthController.login()
  → AuthService.login()
      → UserRepository.findByEmail()
      → passwordEncoder.matches()
      → JwtService.createToken()
      → JwtService.expiryFor()
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/auth/login` · Auth: none  
Body:
```json
{ "email": "admin@oneday.in", "password": "Admin1234!" }
```
Tests tab:
```javascript
var res = pm.response.json();
pm.environment.set("admin_jwt", res.token);
pm.environment.set("jwt", res.token);
```
Expected: `200` → `{ "token": "eyJ...", "role": "ADMIN", "mustChangePassword": false, ... }`

---

### `POST /auth/register` *(C2C customers — instant access)*
```
AuthController.register()
  → AuthService.register()
      → UserRepository.existsByEmail()
      → RoleRepository.findByName("C2C_CUSTOMER")
      → UserRepository.save()
      → RoleAuditLogRepository.save()          [action: CREATE]
      → JwtService.createToken()
      → JwtService.expiryFor()
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/auth/register` · Auth: none  
Body:
```json
{ "email": "priya@example.com", "password": "mypassword", "name": "Priya Sharma" }
```
Tests tab:
```javascript
var res = pm.response.json();
pm.environment.set("jwt", res.token);
```
Expected: `200` → `{ "token": "eyJ...", "role": "C2C_CUSTOMER", ... }`

---

### `POST /auth/api-keys`
```
AuthController.createApiKey()
  → AuthService.createApiKey()
      → ApiKeyRepository.countByUserIdAndActiveTrue()
      → UserRepository.findById()
      → ApiKeyRepository.save()
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/auth/api-keys` · Auth: `Bearer {{jwt}}` *(must be ADMIN / B2B_USER / B2C_CUSTOMER — other roles get 403)*  
Body:
```json
{ "label": "ci-pipeline" }
```
Tests tab:
```javascript
pm.environment.set("apikey_id", pm.response.json().id);
```
Expected: `200` → `{ "id": "...", "label": "ci-pipeline", "rawKey": "od_live_...", ... }` — copy `rawKey`, shown only once

---

### `GET /auth/api-keys`
```
AuthController.listApiKeys()
  → AuthService.listApiKeys()
      → ApiKeyRepository.findAllByUserId()
```

**Postman**  
Method: `GET` · URL: `{{base_url}}/auth/api-keys` · Auth: `Bearer {{jwt}}`  
Expected: `200` → array containing the key just created

---

### `DELETE /auth/api-keys/{keyId}`
```
AuthController.revokeApiKey()
  → AuthService.revokeApiKey()
      → ApiKeyRepository.findById()
      → UserRepository.findById()              [admin check]
      → ApiKeyRepository.save()               [active = false]
```

**Postman**  
Method: `DELETE` · URL: `{{base_url}}/auth/api-keys/{{apikey_id}}` · Auth: `Bearer {{jwt}}`  
Expected: `204` no body

---

## `/users` — UserController

### `POST /users`
```
UserController.createUser()
  → UserService.register()
      → UserRepository.existsByEmail()
      → RoleRepository.findByName()
      → UserRepository.findById()              [actor fetch]
      → UserRepository.save()
      → RoleAuditLogRepository.save()          [action: CREATE]
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/users` · Auth: `Bearer {{admin_jwt}}`  
Body:
```json
{ "name": "Ravi Kumar", "email": "ravi@oneday.in", "password": "temp1234", "role": "DELIVERY_AGENT", "cityId": "DEL" }
```
Tests tab:
```javascript
pm.environment.set("user_id", pm.response.json().id);
```
Expected: `200` → user object with `active: true`

---

### `GET /users/{id}`
```
UserController.getUser()
  → UserService.getUser()
      → UserRepository.findById()
```

**Postman**  
Method: `GET` · URL: `{{base_url}}/users/{{user_id}}` · Auth: `Bearer {{admin_jwt}}`  
Expected: `200` → user details

---

### `GET /users?email=`
```
UserController.getUserByEmail()
  → UserService.getUserByEmail()
      → UserRepository.findByEmail()
```

**Postman**  
Method: `GET` · URL: `{{base_url}}/users?email=ravi%40oneday.in` · Auth: `Bearer {{admin_jwt}}`  
Tests tab:
```javascript
pm.environment.set("user_id", pm.response.json().id);
```
Expected: `200` → user details · `404` if email not found

---

### `PUT /users/{id}/role`
```
UserController.changeRole()
  → UserService.changeRole()
      → UserRepository.findById()              [target fetch]
      → UserRepository.findById()              [actor fetch]
      → RoleRepository.findById()
      → UserRepository.save()
      → RoleAuditLogRepository.save()          [action: GRANT]
```

**Postman**  
Method: `PUT` · URL: `{{base_url}}/users/{{user_id}}/role` · Auth: `Bearer {{admin_jwt}}`  
Body:
```json
{ "newRoleId": "{{role_id}}", "reason": "Promoted to city lead" }
```
Expected: `204` no body  
*Note: populate `role_id` from `GET /roles` first (see below)*

---

### `GET /users/{id}/audit-log`
```
UserController.getAuditLog()
  → UserService.getAuditLog()
      → RoleAuditLogRepository.findByTargetUserIdOrderByCreatedAtDesc()
```

**Postman**  
Method: `GET` · URL: `{{base_url}}/users/{{user_id}}/audit-log` · Auth: `Bearer {{admin_jwt}}`  
Expected: `200` → array of role-change entries

---

### `DELETE /users/{id}`
```
UserController.deactivate()
  → UserService.deactivate()
      → UserRepository.findById()
      → UserRepository.save()                 [active = false]
      → RoleAuditLogRepository.save()          [action: DEACTIVATE]
```

**Postman**  
Method: `DELETE` · URL: `{{base_url}}/users/{{user_id}}` · Auth: `Bearer {{admin_jwt}}`  
Expected: `204` no body

---

### `PUT /users/{id}/reactivate`
```
UserController.reactivate()
  → UserService.reactivate()
      → UserRepository.findById()
      → UserRepository.save()                 [active = true]
      → RoleAuditLogRepository.save()          [action: REACTIVATE]
```

**Postman**  
Method: `PUT` · URL: `{{base_url}}/users/{{user_id}}/reactivate` · Auth: `Bearer {{admin_jwt}}`  
Expected: `204` no body

---

### `POST /users/{id}/reset-password`
```
UserController.resetPassword()
  → AuthService.resetPassword()
      → UserRepository.findById()
      → passwordEncoder.encode()
      → UserRepository.save()                 [mustChangePassword = true]
      → RoleAuditLogRepository.save()          [action: PASSWORD_RESET]
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/users/{{user_id}}/reset-password` · Auth: `Bearer {{admin_jwt}}`  
Body:
```json
{ "newPassword": "Temp@2026!" }
```
Expected: `204` no body

---

### `PUT /users/me/password`
```
UserController.changePassword()
  → AuthService.changePassword()
      → UserRepository.findById()
      → passwordEncoder.matches()
      → passwordEncoder.encode()
      → UserRepository.save()                 [mustChangePassword = false]
```

**Postman**  
Method: `PUT` · URL: `{{base_url}}/users/me/password` · Auth: `Bearer {{jwt}}` (Priya's token)  
Body:
```json
{ "currentPassword": "mypassword", "newPassword": "new-secret-123" }
```
Expected: `204` no body

---

### `PUT /users/me`
```
UserController.updateProfile()
  → UserService.updateProfile()
      → UserRepository.findById()
      → UserRepository.save()
```

**Postman**  
Method: `PUT` · URL: `{{base_url}}/users/me` · Auth: `Bearer {{jwt}}`  
Body:
```json
{ "name": "Priya S. Sharma" }
```
Expected: `204` no body

---

## `/roles` — RoleController

### `POST /roles` *(ADMIN only)*
```
RoleController.createRole()
  → RoleService.createRole()
      → PermissionRepository.findAllByActionIn()
      → RoleRepository.save()
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/roles` · Auth: `Bearer {{admin_jwt}}`  
Body:
```json
{ "name": "REGIONAL_AUDITOR", "displayName": "Regional Auditor", "cityScoped": true, "permissions": ["ORDER_READ", "SHIPMENT_READ", "AUDIT_LOG_READ"] }
```
Tests tab:
```javascript
pm.environment.set("role_id", pm.response.json().id);
```
Expected: `200` → role object

---

### `GET /roles`
```
RoleController.listRoles()
  → RoleService.listAllRoles()
      → RoleRepository.findAllByActiveTrueWithPermissions()   [LEFT JOIN FETCH — 1 query]
```

**Postman**  
Method: `GET` · URL: `{{base_url}}/roles` · Auth: `Bearer {{admin_jwt}}`  
Tests tab (grab a built-in role ID for role-change test):
```javascript
var roles = pm.response.json();
var target = roles.find(r => r.name === "CITY_OPS_MANAGER");
if (target) pm.environment.set("role_id", target.id);
```
Expected: `200` → array of 12+ built-in roles, each with a `permissions` array

---

### `DELETE /roles/{id}` *(ADMIN only)*
```
RoleController.deactivateRole()
  → RoleService.deactivateRole()
      → RoleRepository.findById()
      → UserRepository.existsByRoleId()
      → RoleRepository.save()                 [active = false]
```

**Postman**  
Method: `DELETE` · URL: `{{base_url}}/roles/{{role_id}}` · Auth: `Bearer {{admin_jwt}}`  
Expected: `204` no body · `422` if any active user is still assigned to it

---

## `/auth/request-onboarding`, `/onboarding-requests` — OnboardingController

### `POST /auth/request-onboarding` *(B2B / B2C users — pending approval)*
```
OnboardingController.submit()
  → OnboardingService.submit()
      → UserRepository.existsByEmail()
      → OnboardingRequestRepository.existsByEmail()
      → passwordEncoder.encode()
      → OnboardingRequestRepository.save()          [status: PENDING]
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/auth/request-onboarding` · Auth: none  
Body:
```json
{ "email": "vendor@acme.in", "name": "Acme Corp", "password": "secret123", "requestedRole": "B2B_USER" }
```
Tests tab:
```javascript
pm.environment.set("onboarding_id", pm.response.json().id);
```
Expected: `202` → `{ "status": "PENDING", ... }`

---

### `GET /onboarding-requests`
```
OnboardingController.listAll()
  → OnboardingService.listAll()
      → OnboardingRequestRepository.findAllByOrderByCreatedAtDesc()
```

**Postman**  
Method: `GET` · URL: `{{base_url}}/onboarding-requests` · Auth: `Bearer {{admin_jwt}}`  
Expected: `200` → array of requests, newest first

---

### `POST /onboarding-requests/{id}/approve`
```
OnboardingController.approve()
  → OnboardingService.approve()
      → OnboardingRequestRepository.findById()
      → UserRepository.existsByEmail()
      → RoleRepository.findByName()                 [active check]
      → UserRepository.save()                       [mustChangePassword = true]
      → OnboardingRequestRepository.save()          [status: APPROVED, reviewedBy, reviewedAt]
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/onboarding-requests/{{onboarding_id}}/approve` · Auth: `Bearer {{admin_jwt}}`  
Body: none  
Expected: `204` — vendor can now log in with `mustChangePassword = true`

---

### `POST /onboarding-requests/{id}/reject`
```
OnboardingController.reject()
  → OnboardingService.reject()
      → OnboardingRequestRepository.findById()
      → OnboardingRequestRepository.save()          [status: REJECTED, rejectionReason, reviewedBy, reviewedAt]
```

**Postman**  
Method: `POST` · URL: `{{base_url}}/onboarding-requests/{{onboarding_id}}/reject` · Auth: `Bearer {{admin_jwt}}`  
Body:
```json
{ "reason": "Incomplete business details" }
```
Expected: `204` no body

---

## `/permissions` — PermissionController

### `GET /permissions/check`
```
PermissionController.check()
  → validate: exactly one of userId / email provided (400 if neither or both)
  → if email: UserService.getUserByEmail() → UserRepository.findByEmail()
  → ownership guard: ADMIN/CALL_CENTER_AGENT → any user; others → own identity only (403 otherwise)
  → PermissionService.canDo()
      → UserRepository.findByIdWithPermissions()
      → role.getPermissions().stream()...anyMatch()  [in-memory check]
```

**Postman (by UUID)**  
Method: `GET` · URL: `{{base_url}}/permissions/check?userId={{user_id}}&action=shipment:create&cityId=DEL` · Auth: `Bearer {{admin_jwt}}`  
Expected: `200` → `{ "allowed": true/false, "reason": "..." }`

**Postman (by email)**  
Method: `GET` · URL: `{{base_url}}/permissions/check?email=ravi%40oneday.in&action=shipment:create&cityId=DEL` · Auth: `Bearer {{admin_jwt}}`  
Expected: `200` → `{ "allowed": true/false, "reason": "..." }`  
*Non-privileged tokens may only query their own identity — passing another user returns `403`.*
