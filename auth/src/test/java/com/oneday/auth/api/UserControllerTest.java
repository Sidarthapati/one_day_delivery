package com.oneday.auth.api;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.response.AuditLogResponse;
import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.exception.ForbiddenException;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.security.SecurityConfig;
import com.oneday.auth.service.AuthService;
import com.oneday.auth.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private UserService userService;
    @MockBean private AuthService authService;           // controller dep + SecurityConfig filter
    @MockBean private ApiKeyRepository apiKeyRepository; // SecurityConfig → JwtAuthenticationFilter

    private static final UUID USER_ID = UUID.randomUUID();

    // ── CREATE USER ───────────────────────────────────────────────────────────

    @Test
    void createUser_adminCreatesStationManager_returns200() throws Exception {
        UserResponse resp = new UserResponse(UUID.randomUUID(), "arjun@oneday.in", "Arjun", "STATION_MANAGER", "MUM", true);
        when(userService.register(any(), any(UUID.class))).thenReturn(resp);

        mockMvc.perform(post("/users")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Arjun Sharma",
                                  "email": "arjun@oneday.in",
                                  "password": "Secure#9012",
                                  "role": "STATION_MANAGER",
                                  "cityId": "MUM"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("arjun@oneday.in"))
                .andExpect(jsonPath("$.role").value("STATION_MANAGER"))
                .andExpect(jsonPath("$.cityId").value("MUM"));
    }

    @Test
    void createUser_duplicateEmail_returns409() throws Exception {
        when(userService.register(any(), any(UUID.class)))
                .thenThrow(new EmailAlreadyExistsException("arjun@oneday.in"));

        mockMvc.perform(post("/users")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Arjun",
                                  "email": "arjun@oneday.in",
                                  "password": "Secure#9012",
                                  "role": "STATION_MANAGER",
                                  "cityId": "MUM"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void createUser_cityScopedRoleWithoutCity_returns403() throws Exception {
        when(userService.register(any(), any(UUID.class)))
                .thenThrow(new ForbiddenException("cityId is required for role DELIVERY_ASSOCIATE"));

        mockMvc.perform(post("/users")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Priya",
                                  "email": "priya@test.com",
                                  "password": "Secure#9012",
                                  "role": "DELIVERY_ASSOCIATE"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUser_missingName_returns422() throws Exception {
        mockMvc.perform(post("/users")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "priya@test.com",
                                  "password": "Secure#9012",
                                  "role": "DELIVERY_ASSOCIATE"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createUser_shortPassword_returns422() throws Exception {
        mockMvc.perform(post("/users")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Priya",
                                  "email": "priya@test.com",
                                  "password": "short",
                                  "role": "DELIVERY_ASSOCIATE",
                                  "cityId": "MUM"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createUser_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"x@test.com","password":"password123","role":"DELIVERY_ASSOCIATE","cityId":"MUM"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── CHANGE ROLE ───────────────────────────────────────────────────────────

    @Test
    void changeRole_adminChangesRole_returns204() throws Exception {
        UUID roleId = UUID.randomUUID();
        doNothing().when(userService).changeRole(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(put("/users/{id}/role", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"newRoleId": "%s", "reason": "Promotion"}
                                """, roleId)))
                .andExpect(status().isNoContent());

        verify(userService).changeRole(eq(USER_ID), any(), any(UUID.class));
    }

    @Test
    void changeRole_stationManagerChangesRole_returns204() throws Exception {
        UUID roleId = UUID.randomUUID();
        doNothing().when(userService).changeRole(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(put("/users/{id}/role", USER_ID)
                        .with(user(buildPrincipal("STATION_MANAGER", "MUM")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"newRoleId": "%s", "reason": "Promotion"}
                                """, roleId)))
                .andExpect(status().isNoContent());
    }

    @Test
    void changeRole_forbidden_returns403() throws Exception {
        UUID roleId = UUID.randomUUID();
        doThrow(new ForbiddenException("Station Manager cannot grant ADMIN role"))
                .when(userService).changeRole(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(put("/users/{id}/role", USER_ID)
                        .with(user(buildPrincipal("STATION_MANAGER", "MUM")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"newRoleId": "%s"}
                                """, roleId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void changeRole_missingNewRoleId_returns422() throws Exception {
        mockMvc.perform(put("/users/{id}/role", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Promotion"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void changeRole_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/users/{id}/role", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"newRoleId": "%s"}
                                """, UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    // ── AUDIT LOG ─────────────────────────────────────────────────────────────

    @Test
    void getAuditLog_returns200WithLogs() throws Exception {
        UUID actorId = UUID.randomUUID();
        List<AuditLogResponse> logs = List.of(
                new AuditLogResponse(UUID.randomUUID(), actorId, USER_ID, "GRANT",
                        "DELIVERY_ASSOCIATE", "SUPERVISOR", "MUM", "Strong performance", Instant.now()),
                new AuditLogResponse(UUID.randomUUID(), actorId, USER_ID, "CREATE",
                        null, "DELIVERY_ASSOCIATE", "MUM", null, Instant.now().minusSeconds(3600))
        );
        when(userService.getAuditLog(USER_ID)).thenReturn(logs);

        mockMvc.perform(get("/users/{id}/audit-log", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("GRANT"))
                .andExpect(jsonPath("$[0].newRole").value("SUPERVISOR"))
                .andExpect(jsonPath("$[1].action").value("CREATE"));
    }

    @Test
    void getAuditLog_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users/{id}/audit-log", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── DEACTIVATE ────────────────────────────────────────────────────────────

    @Test
    void deactivate_admin_returns204() throws Exception {
        doNothing().when(userService).deactivate(any(UUID.class), any(UUID.class));

        mockMvc.perform(delete("/users/{id}", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNoContent());

        verify(userService).deactivate(eq(USER_ID), any(UUID.class));
    }

    @Test
    void deactivate_userNotFound_returns404() throws Exception {
        doThrow(new UserNotFoundException("User not found"))
                .when(userService).deactivate(any(UUID.class), any(UUID.class));

        mockMvc.perform(delete("/users/{id}", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivate_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/users/{id}", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── REACTIVATE ────────────────────────────────────────────────────────────

    @Test
    void reactivate_admin_returns204() throws Exception {
        doNothing().when(userService).reactivate(any(UUID.class), any(UUID.class));

        mockMvc.perform(put("/users/{id}/reactivate", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNoContent());

        verify(userService).reactivate(eq(USER_ID), any(UUID.class));
    }

    // ── RESET PASSWORD ────────────────────────────────────────────────────────

    @Test
    void resetPassword_admin_returns204() throws Exception {
        doNothing().when(authService).resetPassword(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(post("/users/{id}/reset-password", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "NewPass1234!"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void resetPassword_shortNewPassword_returns422() throws Exception {
        mockMvc.perform(post("/users/{id}/reset-password", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "short"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void resetPassword_targetNotFound_returns404() throws Exception {
        doThrow(new UserNotFoundException("User not found"))
                .when(authService).resetPassword(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(post("/users/{id}/reset-password", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "NewPass1234!"}
                                """))
                .andExpect(status().isNotFound());
    }

    // ── CHANGE PASSWORD (self) ────────────────────────────────────────────────

    @Test
    void changePassword_success_returns204() throws Exception {
        doNothing().when(authService).changePassword(any(UUID.class), any(), any());

        mockMvc.perform(put("/users/me/password")
                        .with(user(buildPrincipal("DELIVERY_ASSOCIATE", "MUM")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "OldPass1!", "newPassword": "NewPass1234!"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void changePassword_wrongCurrent_returns401() throws Exception {
        doThrow(new com.oneday.auth.exception.BadCredentialsException())
                .when(authService).changePassword(any(UUID.class), any(), any());

        mockMvc.perform(put("/users/me/password")
                        .with(user(buildPrincipal("DELIVERY_ASSOCIATE", "MUM")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "WrongPass!", "newPassword": "NewPass1234!"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_missingCurrentPassword_returns422() throws Exception {
        mockMvc.perform(put("/users/me/password")
                        .with(user(buildPrincipal("DELIVERY_ASSOCIATE", "MUM")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "NewPass1234!"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── UPDATE PROFILE (self) ─────────────────────────────────────────────────

    @Test
    void updateProfile_success_returns204() throws Exception {
        doNothing().when(userService).updateProfile(any(UUID.class), any());

        mockMvc.perform(put("/users/me")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Riya Updated"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void updateProfile_blankName_returns422() throws Exception {
        mockMvc.perform(put("/users/me")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": ""}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET USER ──────────────────────────────────────────────────────────────

    @Test
    void getUser_found_returns200() throws Exception {
        UserResponse resp = new UserResponse(USER_ID, "arjun@oneday.in", "Arjun", "STATION_MANAGER", "MUM", true);
        when(userService.getUser(USER_ID)).thenReturn(resp);

        mockMvc.perform(get("/users/{id}", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("arjun@oneday.in"))
                .andExpect(jsonPath("$.role").value("STATION_MANAGER"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void getUser_notFound_returns404() throws Exception {
        when(userService.getUser(USER_ID)).thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(get("/users/{id}", USER_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUser_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users/{id}", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── GET USER BY EMAIL ─────────────────────────────────────────────────────

    @Test
    void getUserByEmail_admin_returns200() throws Exception {
        UserResponse resp = new UserResponse(USER_ID, "arjun@oneday.in", "Arjun", "STATION_MANAGER", "MUM", true);
        when(userService.getUserByEmail("arjun@oneday.in")).thenReturn(resp);

        mockMvc.perform(get("/users").param("email", "arjun@oneday.in")
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("arjun@oneday.in"))
                .andExpect(jsonPath("$.role").value("STATION_MANAGER"));
    }

    @Test
    void getUserByEmail_callCenterAgent_returns200() throws Exception {
        UserResponse resp = new UserResponse(USER_ID, "arjun@oneday.in", "Arjun", "STATION_MANAGER", "MUM", true);
        when(userService.getUserByEmail("arjun@oneday.in")).thenReturn(resp);

        mockMvc.perform(get("/users").param("email", "arjun@oneday.in")
                        .with(user(buildPrincipal("CALL_CENTER_AGENT", "MUM"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()));
    }

    @Test
    void getUserByEmail_userNotFound_returns404() throws Exception {
        when(userService.getUserByEmail("ghost@test.com"))
                .thenThrow(new UserNotFoundException("User not found: ghost@test.com"));

        mockMvc.perform(get("/users").param("email", "ghost@test.com")
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUserByEmail_forbiddenRole_returns403() throws Exception {
        mockMvc.perform(get("/users").param("email", "arjun@oneday.in")
                        .with(user(buildPrincipal("DELIVERY_ASSOCIATE", "MUM"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getUserByEmail_missingParam_returns400() throws Exception {
        mockMvc.perform(get("/users")
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUserByEmail_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users").param("email", "arjun@oneday.in"))
                .andExpect(status().isUnauthorized());
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private static AuthUserDetails buildPrincipal(String roleName, String cityId) {
        Role role = new Role();
        role.setName(roleName);
        role.setDisplayName(roleName);
        role.setCityScoped(cityId != null);
        role.setBuiltin(true);
        role.setActive(true);
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());

        User user = new User();
        user.setEmail("test@oneday.in");
        user.setPasswordHash("$2a$hash");
        user.setName("Test User");
        user.setRole(role);
        user.setCityId(cityId);
        user.setActive(true);
        user.setMustChangePassword(false);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return new AuthUserDetails(user);
    }
}
