package com.oneday.auth.e2e;

import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.OnboardingSubmitRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Self-service onboarding")
class OnboardingE2eTest extends AuthE2eSupport {

    // A prospective B2B user submits an onboarding request on the public endpoint (no auth) — it
    // lands in PENDING.
    @Test
    void submitOnboarding_isPublicAndPending() throws Exception {
        mvc.perform(asJson(post("/auth/request-onboarding"),
                        new OnboardingSubmitRequest(uniqueEmail(), "Acme Corp", "Signup123!", "+919000000001", "B2B_USER")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedRole").value("B2B_USER"));
    }

    // Full approval flow: a user signs up, an admin approves, and the user can then log in with the
    // role they requested.
    @Test
    void adminApproves_userCanThenLogin() throws Exception {
        String email = uniqueEmail();
        mvc.perform(asJson(post("/auth/request-onboarding"),
                        new OnboardingSubmitRequest(email, "Acme Corp", "Signup123!", "+919000000001", "B2B_USER")))
                .andExpect(status().isAccepted());

        String admin = adminToken();
        String list = mvc.perform(get("/onboarding-requests").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String requestId = idForEmail(list, email);

        mvc.perform(post("/onboarding-requests/{id}/approve", requestId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, "Signup123!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("B2B_USER"));
    }

    // A rejected onboarding request creates no user, so login fails.
    @Test
    void adminRejects_noUserCreated() throws Exception {
        String email = uniqueEmail();
        mvc.perform(asJson(post("/auth/request-onboarding"),
                        new OnboardingSubmitRequest(email, "Reject Me", "Signup123!", "+919000000001", "B2C_CUSTOMER")))
                .andExpect(status().isAccepted());

        String admin = adminToken();
        String list = mvc.perform(get("/onboarding-requests").header("Authorization", bearer(admin)))
                .andReturn().getResponse().getContentAsString();
        String requestId = idForEmail(list, email);

        mvc.perform(post("/onboarding-requests/{id}/reject", requestId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, "Signup123!")))
                .andExpect(status().isUnauthorized());
    }

    // Reviewing onboarding requests is admin-only — a customer is refused → 403.
    @Test
    void listOnboardingRequests_nonAdmin_returns403() throws Exception {
        mvc.perform(get("/onboarding-requests").header("Authorization", bearer(tokenForRole("C2C_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    private String idForEmail(String listJson, String email) throws Exception {
        for (var node : json.readTree(listJson)) {
            if (email.equals(node.get("email").asText())) {
                return node.get("id").asText();
            }
        }
        throw new IllegalStateException("Onboarding request not found for " + email);
    }
}
