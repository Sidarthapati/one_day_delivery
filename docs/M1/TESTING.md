# M1 Auth — Unit Test Coverage

**Total: 174 tests, 0 failures** (`mvn clean install -pl auth`)

---

## How to Run

```bash
# Run all auth tests
mvn test -pl auth

# Clean build (compile + test + install)
mvn clean install -pl auth

# Run a single test class
mvn test -pl auth -Dtest=AuthServiceImplTest

# Run a single test method
mvn test -pl auth -Dtest="AuthServiceImplTest#login_validCredentials_returnsTokenAndRole"

# Run all controller tests only
mvn test -pl auth -Dtest="*ControllerTest"

# Run all service tests only
mvn test -pl auth -Dtest="*ImplTest"
```

---

## Test Structure

```
auth/src/test/java/com/oneday/auth/
├── TestAuthApplication.java              # @SpringBootApplication for @WebMvcTest slice context
├── api/
│   ├── AuthControllerTest.java           # 19 tests — @WebMvcTest slice
│   ├── UserControllerTest.java           # 28 tests — @WebMvcTest slice
│   ├── RoleControllerTest.java           # 13 tests — @WebMvcTest slice
│   └── PermissionControllerTest.java     # 10 tests — @WebMvcTest slice
└── service/impl/
    ├── AuthServiceImplTest.java          # 26 tests — pure Mockito
    ├── UserServiceImplTest.java          # 31 tests — pure Mockito
    ├── JwtServiceImplTest.java           # 14 tests — no mocks (real JwtServiceImpl)
    ├── PermissionServiceImplTest.java    # 18 tests — pure Mockito
    └── RoleServiceImplTest.java          # 15 tests — pure Mockito
```

---

## Service Layer Tests (pure Mockito, no Spring context)

### AuthServiceImplTest — 26 tests

**Login**
| Test | What it verifies |
|---|---|
| `login_validCredentials_returnsTokenAndRole` | Returns JWT + role name on success |
| `login_emailNotFound_throwsBadCredentials` | Throws `BadCredentialsException` when email unknown |
| `login_inactiveUser_throwsBadCredentials` | Throws `BadCredentialsException` when account deactivated |
| `login_wrongPassword_throwsBadCredentials` | Throws `BadCredentialsException` on wrong password |
| `login_sameExceptionTypeForWrongEmailAndWrongPassword` | Both failure paths produce identical exception (no user enumeration) |

**Register (self-register as B2C)**
| Test | What it verifies |
|---|---|
| `register_newUser_createsB2cCustomerAndReturnsToken` | Creates user with B2C_CUSTOMER role, returns JWT |
| `register_duplicateEmail_throwsEmailAlreadyExists` | Throws `EmailAlreadyExistsException` |
| `register_b2cRoleNotSeeded_throwsRoleNotFound` | Throws `RoleNotFoundException` when DB seed missing |

**API Key management**
| Test | What it verifies |
|---|---|
| `createApiKey_firstKey_returnsRawKeyOnce` | Raw key returned exactly once at creation (not stored) |
| `createApiKey_rawKeysAreUniqueAcrossCalls` | Two consecutive keys have different raw values |
| `createApiKey_capAt10_throwsApiKeyCapExceeded` | 10-key cap enforced |
| `createApiKey_exactly9Keys_allowsCreation` | 9 existing keys allows one more |
| `revokeApiKey_ownerRevokesOwnKey_success` | Owner can revoke their own key |
| `revokeApiKey_adminRevokesOthersKey_success` | ADMIN can revoke any user's key |
| `revokeApiKey_nonOwnerNonAdmin_throwsForbidden` | Non-owner, non-admin gets `ForbiddenException` |
| `revokeApiKey_keyNotFound_throwsUserNotFound` | Missing key throws `UserNotFoundException` |
| `listApiKeys_returnsAllKeysForUser` | Returns all keys for the calling user |
| `listApiKeys_noKeys_returnsEmpty` | Empty list when user has no keys |

**Password operations**
| Test | What it verifies |
|---|---|
| `resetPassword_success_setsHashAndMustChangeFlag` | Admin reset sets new hash + `mustChangePassword=true` |
| `resetPassword_targetNotFound_throwsUserNotFound` | Missing target throws `UserNotFoundException` |
| `changePassword_success_clearsForceFlag` | Self-change clears `mustChangePassword` flag |
| `changePassword_wrongCurrentPassword_throwsBadCredentials` | Wrong current password throws `BadCredentialsException` |

**Token validation**
| Test | What it verifies |
|---|---|
| `validateToken_validToken_returnsActiveUser` | Valid JWT resolves to active User entity |
| `validateToken_jwtException_throwsBadCredentials` | Expired/tampered JWT → `BadCredentialsException` |
| `validateToken_malformedSubject_throwsBadCredentials` | Non-UUID subject → `BadCredentialsException` |
| `validateToken_userDeactivatedAfterTokenIssued_throwsUserNotFound` | Valid JWT but user deactivated → `UserNotFoundException` |

---

### UserServiceImplTest — 31 tests

**Admin creates users**
| Test | What it verifies |
|---|---|
| `register_adminCreatesStationManager_withCity_success` | SM created with cityId, audit log written |
| `register_adminCreatesB2bUser_withoutCity_success` | B2B_USER created without cityId |
| `register_cityScopedRoleWithoutCity_throwsForbidden` | City-scoped role requires cityId |
| `register_cityScopedRoleWithBlankCity_throwsForbidden` | Blank cityId treated same as missing |
| `register_duplicateEmail_throwsEmailAlreadyExists` | Duplicate email rejected |
| `register_inactiveRole_throwsRoleNotFound` | Inactive role treated as not found |
| `register_roleNotFound_throwsRoleNotFound` | Unknown role throws `RoleNotFoundException` |
| `register_stationManagerCreatesUserInOwnCity_success` | SM can create users for their own city |
| `register_stationManagerCreatesUserInDifferentCity_throwsForbidden` | SM cannot create users for other city |
| `register_newUserGetsMustChangePasswordTrue` | Admin-created users must change password on first login |
| `register_auditLogActionIsCreate` | CREATE audit log entry written on user creation |

**Role change**
| Test | What it verifies |
|---|---|
| `changeRole_adminPromotesDaToSupervisor_success` | ADMIN can change any role |
| `changeRole_stationManagerPromotesInOwnCity_success` | SM can change roles within their city |
| `changeRole_stationManagerGrantsAdmin_throwsForbidden` | SM cannot grant ADMIN role |
| `changeRole_stationManagerModifiesAdminUser_throwsForbidden` | SM cannot touch ADMIN users |
| `changeRole_stationManagerModifiesPeerSM_throwsForbidden` | SM cannot modify another SM |
| `changeRole_stationManagerModifiesDifferentCity_throwsForbidden` | SM cannot modify users in other city |
| `changeRole_targetNotFound_throwsUserNotFound` | Unknown target throws `UserNotFoundException` |
| `changeRole_roleNotFound_throwsRoleNotFound` | Unknown target role throws `RoleNotFoundException` |
| `changeRole_cityScopedNewRoleTargetHasNoCity_throwsForbidden` | City-scoped target role requires target to have cityId |

**Lifecycle**
| Test | What it verifies |
|---|---|
| `deactivate_setsActiveFalseAndWritesAuditLog` | Deactivate flips `active=false`, writes DEACTIVATE audit entry |
| `deactivate_userNotFound_throwsUserNotFound` | Missing user throws `UserNotFoundException` |
| `reactivate_setsActiveTrueAndWritesAuditLog` | Reactivate flips `active=true`, writes REACTIVATE audit entry |
| `reactivate_userNotFound_throwsUserNotFound` | Missing user throws `UserNotFoundException` |

**Profile & queries**
| Test | What it verifies |
|---|---|
| `updateProfile_updatesNameAndSaves` | Name update persisted |
| `updateProfile_userNotFound_throwsUserNotFound` | Missing user throws `UserNotFoundException` |
| `getUser_found_returnsCorrectResponse` | Returns full `UserResponse` DTO |
| `getUser_cityScoped_includesCityIdInResponse` | City-scoped user has `cityId` in response |
| `getUser_notFound_throwsUserNotFound` | Missing user throws `UserNotFoundException` |
| `getAuditLog_returnsLogsNewestFirst` | Audit log sorted descending by timestamp |
| `getAuditLog_noLogs_returnsEmpty` | Empty list when no history |

---

### JwtServiceImplTest — 14 tests

No mocks — instantiates `JwtServiceImpl` directly with a test secret key.

**Token creation**
| Test | What it verifies |
|---|---|
| `createToken_returnsNonBlankToken` | Returns a valid 3-part JWT string |
| `createToken_subjectIsUserId` | `sub` claim equals user UUID string |
| `createToken_claimsContainRoleAndCityId` | `role` and `cityId` claims set correctly |
| `createToken_adminHasNullCityId` | Non-city-scoped roles have no `cityId` claim |
| `createToken_claimsContainName` | `name` claim is set |
| `createToken_mustChangePasswordClaimPresent` | `mustChangePassword` claim is true when flag is set |

**Token parsing**
| Test | What it verifies |
|---|---|
| `parseToken_validToken_returnsCorrectClaims` | All claims round-trip correctly |
| `parseToken_tamperedToken_throwsJwtException` | Payload tampering detected |
| `parseToken_wrongSecret_throwsJwtException` | Token from different secret rejected |
| `parseToken_expiredToken_throwsJwtException` | Zero-hour expiry produces expired token |
| `parseToken_garbage_throwsJwtException` | Non-JWT string throws `JwtException` |
| `parseToken_blankString_throwsJwtException` | Empty string throws exception |

**Expiry calculation**
| Test | What it verifies |
|---|---|
| `expiryFor_returns8HoursFromNow` | Default 8-hour expiry within ±5 seconds |
| `expiryFor_customExpiry_returnsCorrectDuration` | 2-hour service produces 2-hour expiry |

---

### PermissionServiceImplTest — 18 tests

Scenario-driven tests aligned with `docs/M1/SCENARIOS.md`.

| Test | Scenario |
|---|---|
| `canDo_userNotFound_returnsFalse` | Unknown userId → `allowed=false`, reason contains "not found" |
| `canDo_inactiveUser_returnsFalse` | Deactivated user → `allowed=false`, reason contains "inactive" |
| `canDo_adminHasPermission_noCityParam_returnsTrue` | ADMIN can perform action without city constraint |
| `canDo_roleDoesNotHavePermission_returnsFalse` | Role lacks the requested permission |
| `canDo_emptyPermissionSet_returnsFalse` | Role with empty permissions always denied |
| `canDo_deliveryAssociateMumbai_viewQueueMumbai_returnsTrue` | Priya (DA, MUM) can view MUM queue |
| `canDo_deliveryAssociateMumbai_viewQueueDelhi_returnsFalse` | Priya (DA, MUM) cannot access DEL queue |
| `canDo_adminShipmentView_anyCity_returnsTrue` | ADMIN is not city-scoped, can touch any city |
| `canDo_cityScopedRole_noCityParam_skipsCheck_returnsTrue` | null cityId param → city check skipped |
| `canDo_cityScopedRole_blankCityParam_skipsCheck_returnsTrue` | Blank cityId param → city check skipped |
| `canDo_hubOperator_shipmentCreate_returnsFalse` | HUB_OPERATOR cannot book shipments |
| `canDo_b2bUser_shipmentCreate_noCity_returnsTrue` | B2B_USER can create shipments (not city-scoped) |
| `canDo_airlineGha_manifestView_returnsTrue` | AIRLINE_GHA can view manifests |
| `canDo_airlineGha_shipmentCreate_returnsFalse` | AIRLINE_GHA cannot book shipments |
| `canDo_stationManager_auditViewCity_matchingCity_returnsTrue` | SM can view audit for their own city |
| `canDo_stationManager_auditViewCity_differentCity_returnsFalse` | SM cannot view audit for other city |
| `canDo_b2cCustomer_shipmentTrackOwn_returnsTrue` | B2C customer can track their own shipment |
| `canDo_cronDriver_cronRunConfirm_ownCity_returnsTrue` | CRON_DRIVER can confirm runs in their city |

---

### RoleServiceImplTest — 15 tests

**Create role**
| Test | What it verifies |
|---|---|
| `createRole_validPermissions_returnsRoleResponse` | Returns mapped `RoleResponse` on success |
| `createRole_nameSavedAsUpperCase` | Role name uppercased before persisting |
| `createRole_cityScoped_savedWithCityScopedTrue` | `cityScoped` flag set correctly |
| `createRole_notBuiltin_savedWithBuiltinFalse` | Custom roles are never builtin |
| `createRole_invalidPermission_throwsForbidden` | Partial permission match → `ForbiddenException` |
| `createRole_noPermissionsFound_throwsForbidden` | No permissions matched → `ForbiddenException` |

**List roles**
| Test | What it verifies |
|---|---|
| `listAllRoles_returnsOnlyActiveRoles` | Only `active=true` roles returned |
| `listAllRoles_empty_returnsEmptyList` | Empty list when no active roles |
| `listAllRoles_roleProperties_mappedCorrectly` | All DTO fields mapped: builtin, cityScoped, displayName |
| `listAllRoles_includes12BuiltinRoles` | All 12 seeded roles listed |

**Deactivate role**
| Test | What it verifies |
|---|---|
| `deactivateRole_customUnusedRole_success` | Custom unused role gets `active=false` |
| `deactivateRole_builtinRole_throwsForbidden` | Built-in roles cannot be deactivated |
| `deactivateRole_roleInUse_throwsRoleInUse` | Role assigned to users → `RoleInUseException` |
| `deactivateRole_notFound_throwsRoleNotFound` | Missing role → `RoleNotFoundException` |
| `deactivateRole_allBuiltinRoles_throwForbidden` | All 12 built-in roles individually blocked |

---

## Controller Layer Tests (`@WebMvcTest` — Spring MVC slice)

Each controller test loads `SecurityConfig` via `@Import(SecurityConfig.class)` and mocks `AuthService` + `ApiKeyRepository` (needed by `JwtAuthenticationFilter`). Authenticated requests use `SecurityMockMvcRequestPostProcessors.user(AuthUserDetails)`.

### AuthControllerTest — 19 tests

**Endpoints:** `GET /auth/health`, `POST /auth/login`, `POST /auth/register`, `POST /auth/api-keys`, `GET /auth/api-keys`, `DELETE /auth/api-keys/{keyId}`

| Test | HTTP | Expected |
|---|---|---|
| `health_returnsUp` | GET /auth/health | 200 `{status: UP}` |
| `login_validCredentials_returns200WithToken` | POST /auth/login | 200 with token + role |
| `login_wrongCredentials_returns401` | POST /auth/login | 401 |
| `login_missingEmail_returns422` | POST /auth/login | 422 (validation) |
| `login_missingPassword_returns422` | POST /auth/login | 422 |
| `login_invalidEmailFormat_returns422` | POST /auth/login | 422 |
| `login_noBody_returns400` | POST /auth/login | 400 |
| `register_validRequest_returns200WithToken` | POST /auth/register | 200 with token |
| `register_duplicateEmail_returns409` | POST /auth/register | 409 |
| `register_shortPassword_returns422` | POST /auth/register | 422 |
| `register_missingName_returns422` | POST /auth/register | 422 |
| `createApiKey_authenticated_returns200WithRawKey` | POST /auth/api-keys (auth) | 200 with rawKey |
| `createApiKey_capExceeded_returns422` | POST /auth/api-keys (auth) | 422 |
| `createApiKey_missingLabel_returns422` | POST /auth/api-keys (auth) | 422 |
| `createApiKey_unauthenticated_returns401` | POST /auth/api-keys | 401 |
| `listApiKeys_authenticated_returnsKeyList` | GET /auth/api-keys (auth) | 200 array |
| `listApiKeys_unauthenticated_returns401` | GET /auth/api-keys | 401 |
| `revokeApiKey_owner_returns204` | DELETE /auth/api-keys/{id} (auth) | 204 |
| `revokeApiKey_unauthenticated_returns401` | DELETE /auth/api-keys/{id} | 401 |

### UserControllerTest — 28 tests

**Endpoints:** `POST /users`, `PUT /users/{id}/role`, `GET /users/{id}/audit-log`, `DELETE /users/{id}`, `PUT /users/{id}/reactivate`, `POST /users/{id}/reset-password`, `PUT /users/me/password`, `PUT /users/me`, `GET /users/{id}`

| Test | Expected |
|---|---|
| `createUser_adminCreatesStationManager_returns200` | 200 with user details incl. cityId |
| `createUser_duplicateEmail_returns409` | 409 |
| `createUser_cityScopedRoleWithoutCity_returns403` | 403 (service-level ForbiddenException) |
| `createUser_missingName_returns422` | 422 |
| `createUser_shortPassword_returns422` | 422 |
| `createUser_unauthenticated_returns401` | 401 |
| `changeRole_adminChangesRole_returns204` | 204 |
| `changeRole_stationManagerChangesRole_returns204` | 204 |
| `changeRole_forbidden_returns403` | 403 (service-level ForbiddenException) |
| `changeRole_missingNewRoleId_returns422` | 422 |
| `changeRole_unauthenticated_returns401` | 401 |
| `getAuditLog_returns200WithLogs` | 200 with 2 entries, fields mapped |
| `getAuditLog_unauthenticated_returns401` | 401 |
| `deactivate_admin_returns204` | 204 |
| `deactivate_userNotFound_returns404` | 404 |
| `deactivate_unauthenticated_returns401` | 401 |
| `reactivate_admin_returns204` | 204 |
| `resetPassword_admin_returns204` | 204 |
| `resetPassword_shortNewPassword_returns422` | 422 |
| `resetPassword_targetNotFound_returns404` | 404 |
| `changePassword_success_returns204` | 204 |
| `changePassword_wrongCurrent_returns401` | 401 |
| `changePassword_missingCurrentPassword_returns422` | 422 |
| `updateProfile_success_returns204` | 204 |
| `updateProfile_blankName_returns422` | 422 |
| `getUser_found_returns200` | 200 with all user fields |
| `getUser_notFound_returns404` | 404 |
| `getUser_unauthenticated_returns401` | 401 |

### RoleControllerTest — 13 tests

**Endpoints:** `POST /roles`, `GET /roles`, `DELETE /roles/{id}`

| Test | Expected |
|---|---|
| `createRole_admin_returns200WithRoleDetails` | 200 with role incl. cityScoped, builtin |
| `createRole_invalidPermissions_returns403` | 403 |
| `createRole_missingName_returns422` | 422 |
| `createRole_emptyPermissions_returns422` | 422 |
| `createRole_unauthenticated_returns401` | 401 |
| `listRoles_authenticated_returnsAllActiveRoles` | 200 with 3 roles, cityScoped flag checked |
| `listRoles_emptyList_returnsEmptyArray` | 200 `[]` |
| `listRoles_unauthenticated_returns401` | 401 |
| `deactivateRole_admin_returns204` | 204 |
| `deactivateRole_builtinRole_returns403` | 403 |
| `deactivateRole_roleInUse_returns422` | 422 |
| `deactivateRole_notFound_returns404` | 404 |
| `deactivateRole_unauthenticated_returns401` | 401 |

### PermissionControllerTest — 10 tests

**Endpoint:** `GET /permissions/check?userId=&action=&cityId=`

| Test | Scenario |
|---|---|
| `check_allowed_returns200WithAllowedTrue` | ADMIN can do `shipment:view` in MUM |
| `check_notAllowed_returns200WithAllowedFalse` | City mismatch → false with reason |
| `check_withoutCityId_passes_noCityConstraint` | No cityId param → city check skipped |
| `check_inactiveUser_returns200WithAllowedFalse` | Inactive user → false |
| `check_roleLacksPermission_returns200WithAllowedFalse` | HUB_OPERATOR cannot create shipments |
| `check_missingUserId_returns400` | Missing required param |
| `check_missingAction_returns400` | Missing required param |
| `check_unauthenticated_returns401` | No credentials |
| `check_adminPermission_anyCity_returnsAllowedTrue` | ADMIN not city-scoped, any city allowed |
| `check_deliveryAssociateMumbai_delhiQueue_returnsAllowedFalse` | DA MUM cannot access DEL queue |

---

## Key Implementation Notes

### Java 25 + Mockito Constraints
- JPA entities extending `BaseEntity` (`Role`, `User`, `ApiKey`) **cannot** be mocked with Mockito on Java 25 (byte-buddy limitation). Use real objects with `ReflectionTestUtils.setField(entity, "id", uuid)` to set generated-value fields.
- `io.jsonwebtoken.Claims` (extends `Map<String, Object>`) **cannot** be mocked. Use `new DefaultClaims(Map.of(Claims.SUBJECT, subject))` instead.

### Service Impl Visibility
All service implementations are **package-private** (`class AuthServiceImpl implements AuthService`). Tests must be in the same package: `com.oneday.auth.service.impl`.

### Controller Test Setup
Every `@WebMvcTest` class requires:
```java
@WebMvcTest(MyController.class)
@Import(SecurityConfig.class)          // loads custom JWT security chain
class MyControllerTest {
    @MockBean private AuthService authService;           // JwtAuthenticationFilter dep
    @MockBean private ApiKeyRepository apiKeyRepository; // JwtAuthenticationFilter dep
}
```
`TestAuthApplication` in `auth/src/test/java/com/oneday/auth/` provides the `@SpringBootConfiguration` that `@WebMvcTest` requires to bootstrap.
