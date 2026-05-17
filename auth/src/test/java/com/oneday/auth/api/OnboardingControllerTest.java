package com.oneday.auth.api;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.response.OnboardingRequestResponse;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.exception.OnboardingRequestAlreadyProcessedException;
import com.oneday.auth.exception.OnboardingRequestNotFoundException;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.security.SecurityConfig;
import com.oneday.auth.service.AuthService;
import com.oneday.auth.service.OnboardingService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OnboardingController.class)
@Import(SecurityConfig.class)
class OnboardingControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private OnboardingService onboardingService;
    @MockBean private AuthService authService;
    @MockBean private ApiKeyRepository apiKeyRepository;

    private static final UUID REQUEST_ID = UUID.randomUUID();

    // ── POST /auth/request-onboarding (public) ────────────────────────────────

    @Test
    void submit_validB2bRequest_returns202WithPendingStatus() throws Exception {
        OnboardingRequestResponse resp = new OnboardingRequestResponse(
                REQUEST_ID, "corp@example.com", "Corp User", "B2B_USER",
                "PENDING", null, null, null, Instant.now());
        when(onboardingService.submit(any())).thenReturn(resp);

        mockMvc.perform(post("/auth/request-onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "corp@example.com",
                                  "name": "Corp User",
                                  "password": "Secure1234!",
                                  "requestedRole": "B2B_USER"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedRole").value("B2B_USER"))
                .andExpect(jsonPath("$.email").value("corp@example.com"));
    }

    @Test
    void submit_validB2cRequest_returns202() throws Exception {
        OnboardingRequestResponse resp = new OnboardingRequestResponse(
                REQUEST_ID, "biz@example.com", "Biz User", "B2C_CUSTOMER",
                "PENDING", null, null, null, Instant.now());
        when(onboardingService.submit(any())).thenReturn(resp);

        mockMvc.perform(post("/auth/request-onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "biz@example.com",
                                  "name": "Biz User",
                                  "password": "Secure1234!",
                                  "requestedRole": "B2C_CUSTOMER"
                                }
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    void submit_noAuthRequired_unauthenticatedSucceeds() throws Exception {
        OnboardingRequestResponse resp = new OnboardingRequestResponse(
                REQUEST_ID, "corp@example.com", "Corp User", "B2B_USER",
                "PENDING", null, null, null, Instant.now());
        when(onboardingService.submit(any())).thenReturn(resp);

        // No .with(user(...)) — should still reach the endpoint
        mockMvc.perform(post("/auth/request-onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "corp@example.com",
                                  "name": "Corp User",
                                  "password": "Secure1234!",
                                  "requestedRole": "B2B_USER"
                                }
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    void submit_invalidRole_returns422() throws Exception {
        mockMvc.perform(post("/auth/request-onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "corp@example.com",
                                  "name": "Corp User",
                                  "password": "Secure1234!",
                                  "requestedRole": "C2C_CUSTOMER"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void submit_duplicateEmail_returns409() throws Exception {
        when(onboardingService.submit(any()))
                .thenThrow(new EmailAlreadyExistsException("corp@example.com"));

        mockMvc.perform(post("/auth/request-onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "corp@example.com",
                                  "name": "Corp User",
                                  "password": "Secure1234!",
                                  "requestedRole": "B2B_USER"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void submit_shortPassword_returns422() throws Exception {
        mockMvc.perform(post("/auth/request-onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "corp@example.com",
                                  "name": "Corp User",
                                  "password": "short",
                                  "requestedRole": "B2B_USER"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void submit_invalidEmail_returns422() throws Exception {
        mockMvc.perform(post("/auth/request-onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "name": "Corp User",
                                  "password": "Secure1234!",
                                  "requestedRole": "B2B_USER"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void submit_missingName_returns422() throws Exception {
        mockMvc.perform(post("/auth/request-onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "corp@example.com",
                                  "password": "Secure1234!",
                                  "requestedRole": "B2B_USER"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET /onboarding-requests (ADMIN only) ─────────────────────────────────

    @Test
    void listAll_admin_returns200WithList() throws Exception {
        List<OnboardingRequestResponse> list = List.of(
                new OnboardingRequestResponse(REQUEST_ID, "corp@example.com", "Corp", "B2B_USER",
                        "PENDING", null, null, null, Instant.now()),
                new OnboardingRequestResponse(UUID.randomUUID(), "biz@example.com", "Biz", "B2C_CUSTOMER",
                        "APPROVED", null, UUID.randomUUID(), Instant.now(), Instant.now().minusSeconds(3600))
        );
        when(onboardingService.listAll()).thenReturn(list);

        mockMvc.perform(get("/onboarding-requests")
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("APPROVED"));
    }

    @Test
    void listAll_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/onboarding-requests")
                        .with(user(buildPrincipal("B2B_USER", null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAll_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/onboarding-requests"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /onboarding-requests/{id}/approve (ADMIN only) ──────────────────

    @Test
    void approve_admin_returns204() throws Exception {
        doNothing().when(onboardingService).approve(any(UUID.class), any(UUID.class));

        mockMvc.perform(post("/onboarding-requests/{id}/approve", REQUEST_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNoContent());
    }

    @Test
    void approve_requestNotFound_returns404() throws Exception {
        doThrow(new OnboardingRequestNotFoundException("Onboarding request not found"))
                .when(onboardingService).approve(any(UUID.class), any(UUID.class));

        mockMvc.perform(post("/onboarding-requests/{id}/approve", REQUEST_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void approve_alreadyProcessed_returns409() throws Exception {
        doThrow(new OnboardingRequestAlreadyProcessedException())
                .when(onboardingService).approve(any(UUID.class), any(UUID.class));

        mockMvc.perform(post("/onboarding-requests/{id}/approve", REQUEST_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void approve_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/onboarding-requests/{id}/approve", REQUEST_ID)
                        .with(user(buildPrincipal("STATION_MANAGER", "MUM"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void approve_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/onboarding-requests/{id}/approve", REQUEST_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /onboarding-requests/{id}/reject (ADMIN only) ───────────────────

    @Test
    void reject_admin_withReason_returns204() throws Exception {
        doNothing().when(onboardingService).reject(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(post("/onboarding-requests/{id}/reject", REQUEST_ID)
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "No service agreement in place"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void reject_admin_noBody_returns204() throws Exception {
        doNothing().when(onboardingService).reject(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(post("/onboarding-requests/{id}/reject", REQUEST_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNoContent());
    }

    @Test
    void reject_requestNotFound_returns404() throws Exception {
        doThrow(new OnboardingRequestNotFoundException("Onboarding request not found"))
                .when(onboardingService).reject(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(post("/onboarding-requests/{id}/reject", REQUEST_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void reject_alreadyProcessed_returns409() throws Exception {
        doThrow(new OnboardingRequestAlreadyProcessedException())
                .when(onboardingService).reject(any(UUID.class), any(), any(UUID.class));

        mockMvc.perform(post("/onboarding-requests/{id}/reject", REQUEST_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void reject_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/onboarding-requests/{id}/reject", REQUEST_ID)
                        .with(user(buildPrincipal("C2C_CUSTOMER", null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reject_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/onboarding-requests/{id}/reject", REQUEST_ID))
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
