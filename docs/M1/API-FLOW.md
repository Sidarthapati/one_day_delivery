# M1 Auth — Controller → Service → Repository Flow

---

## `/auth` — AuthController

### `GET /auth/health`
```
AuthController.health()
  → Map.of("status", "UP")          [no service/repo call]
```

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

---

### `POST /auth/register`
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

---

### `POST /auth/api-keys`
```
AuthController.createApiKey()
  → AuthService.createApiKey()
      → ApiKeyRepository.countByUserIdAndActiveTrue()
      → UserRepository.findById()
      → ApiKeyRepository.save()
```

---

### `GET /auth/api-keys`
```
AuthController.listApiKeys()
  → AuthService.listApiKeys()
      → ApiKeyRepository.findAllByUserId()
```

---

### `DELETE /auth/api-keys/{keyId}`
```
AuthController.revokeApiKey()
  → AuthService.revokeApiKey()
      → ApiKeyRepository.findById()
      → UserRepository.findById()              [admin check]
      → ApiKeyRepository.save()               [active = false]
```

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

---

### `GET /users/{id}`
```
UserController.getUser()
  → UserService.getUser()
      → UserRepository.findById()
```

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

---

### `GET /users/{id}/audit-log`
```
UserController.getAuditLog()
  → UserService.getAuditLog()
      → RoleAuditLogRepository.findByTargetUserIdOrderByCreatedAtDesc()
```

---

### `DELETE /users/{id}`
```
UserController.deactivate()
  → UserService.deactivate()
      → UserRepository.findById()
      → UserRepository.save()                 [active = false]
      → RoleAuditLogRepository.save()          [action: DEACTIVATE]
```

---

### `PUT /users/{id}/reactivate`
```
UserController.reactivate()
  → UserService.reactivate()
      → UserRepository.findById()
      → UserRepository.save()                 [active = true]
      → RoleAuditLogRepository.save()          [action: REACTIVATE]
```

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

---

### `PUT /users/me`
```
UserController.updateProfile()
  → UserService.updateProfile()
      → UserRepository.findById()
      → UserRepository.save()
```

---

## `/roles` — RoleController

### `POST /roles`
```
RoleController.createRole()
  → RoleService.createRole()
      → PermissionRepository.findAllByActionIn()
      → RoleRepository.save()
```

---

### `GET /roles`
```
RoleController.listRoles()
  → RoleService.listAllRoles()
      → RoleRepository.findAllByActiveTrue()
```

---

### `DELETE /roles/{id}`
```
RoleController.deactivateRole()
  → RoleService.deactivateRole()
      → RoleRepository.findById()
      → UserRepository.existsByRoleId()
      → RoleRepository.save()                 [active = false]
```

---

## `/auth/request-onboarding`, `/onboarding-requests` — OnboardingController

### `POST /auth/request-onboarding`
```
OnboardingController.submit()
  → OnboardingService.submit()
      → UserRepository.existsByEmail()
      → OnboardingRequestRepository.existsByEmail()
      → passwordEncoder.encode()
      → OnboardingRequestRepository.save()          [status: PENDING]
```

---

### `GET /onboarding-requests`
```
OnboardingController.listAll()
  → OnboardingService.listAll()
      → OnboardingRequestRepository.findAllByOrderByCreatedAtDesc()
```

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

---

### `POST /onboarding-requests/{id}/reject`
```
OnboardingController.reject()
  → OnboardingService.reject()
      → OnboardingRequestRepository.findById()
      → OnboardingRequestRepository.save()          [status: REJECTED, rejectionReason, reviewedBy, reviewedAt]
```

---

## `/permissions` — PermissionController

### `GET /permissions/check`
```
PermissionController.check()
  → PermissionService.canDo()
      → UserRepository.findByIdWithPermissions()
      → role.getPermissions().stream()...anyMatch()  [in-memory check]
```
