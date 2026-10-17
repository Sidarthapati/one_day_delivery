package com.oneday.auth.e2e;

import com.oneday.auth.dto.request.CandidateBankRequest;
import com.oneday.auth.dto.request.CandidateLocationRequest;
import com.oneday.auth.dto.request.CandidatePersonalRequest;
import com.oneday.auth.dto.request.DaOnboardingInviteRequest;
import com.oneday.common.domain.Shift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E · Self-service DA onboarding funnel (slice S1). Drives the whole flow through the real security
 * chain + real Postgres: admin opens an invite → the applicant completes the public wizard by token
 * (no auth) → submits → the admin approves → a real DELIVERY_ASSOCIATE is provisioned and can log in.
 */
@DisplayName("E2E · DA onboarding funnel")
class DaOnboardingE2eTest extends AuthE2eSupport {

    @Test
    void fullFunnel_inviteWizardSubmitApprove_provisionsLoginableDa() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();

        // 1. Admin opens the invite.
        String inviteBody = mvc.perform(asJson(
                        post("/das/onboarding/invite").header("Authorization", bearer(admin)),
                        new DaOnboardingInviteRequest(email, "Riya", "Kumar", "+919000000009", "DEL")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(inviteBody).get("inviteToken").asText();
        String candidateId = json.readTree(inviteBody).get("candidateId").asText();

        // 2. Applicant reads the wizard by token — no auth needed.
        mvc.perform(get("/public/onboarding/{t}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 3. Applicant fills the steps.
        mvc.perform(asJson(put("/public/onboarding/{t}/personal", token),
                        new CandidatePersonalRequest("Riya", "Kumar", "+919000000009",
                                LocalDate.of(1998, 5, 1), "111122223333", "ABCDE1234F", "DL-0420110149646")))
                .andExpect(status().isOk());
        mvc.perform(asJson(put("/public/onboarding/{t}/location", token),
                        new CandidateLocationRequest("DEL", "Delhi North Hub", Shift.SHIFT_1)))
                .andExpect(status().isOk());
        mvc.perform(asJson(put("/public/onboarding/{t}/bank", token),
                        new CandidateBankRequest("50100123456789", "HDFC0000123", "Riya Kumar")))
                .andExpect(status().isOk());
        mvc.perform(post("/public/onboarding/{t}/agreement/accept", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreementAcceptedAt").isNotEmpty());
        mvc.perform(post("/public/onboarding/{t}/training/ack", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainingAckAt").isNotEmpty());

        // 3b. All mandatory documents uploaded (seeded directly — see AuthE2eSupport).
        uploadAllRequiredDocs(candidateId);

        // 4. Applicant submits → BGV kicks off.
        mvc.perform(post("/public/onboarding/{t}/submit", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BGV_IN_PROGRESS"));

        // 4b. BGV runs (mock): 6 checks created; a reviewer refresh resolves them GREEN and the clean
        //     candidate advances into the pre-approval queue.
        mvc.perform(get("/das/onboarding/candidates/{id}/bgv", candidateId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"));
        mvc.perform(post("/das/onboarding/candidates/{id}/bgv/refresh", candidateId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("GREEN"));
        mvc.perform(get("/das/onboarding/candidates/{id}", candidateId).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // 5. Candidate shows up in the admin queue filtered by status.
        mvc.perform(get("/das/onboarding/candidates").param("status", "PENDING_APPROVAL")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + candidateId + "')]").exists());

        // 6. Admin approves → provisions a DA + returns the temp password.
        String approveBody = mvc.perform(post("/das/onboarding/candidates/{id}/approve", candidateId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tempPassword").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String tempPassword = json.readTree(approveBody).get("tempPassword").asText();

        // 7. Candidate is now ONBOARDED with an employee id + provisioned user.
        mvc.perform(get("/das/onboarding/candidates/{id}", candidateId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONBOARDED"))
                .andExpect(jsonPath("$.employeeId").value(org.hamcrest.Matchers.startsWith("GD-DEL-")))
                .andExpect(jsonPath("$.provisionedUserId").isNotEmpty());

        // 8. The new DA can log in with the temp password and carries the DELIVERY_ASSOCIATE role.
        mvc.perform(asJson(post("/auth/login"),
                        new com.oneday.auth.dto.request.LoginRequest(email, tempPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DELIVERY_ASSOCIATE"));
    }

    @Test
    void submit_missingCityAndShift_isRejected422() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        String inviteBody = mvc.perform(asJson(
                        post("/das/onboarding/invite").header("Authorization", bearer(admin)),
                        new DaOnboardingInviteRequest(email, "No", "City", null, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(inviteBody).get("inviteToken").asText();

        // Only personal filled — no city/shift → cannot submit.
        mvc.perform(asJson(put("/public/onboarding/{t}/personal", token),
                        new CandidatePersonalRequest("No", "City", null, null, null, null, null)))
                .andExpect(status().isOk());
        mvc.perform(post("/public/onboarding/{t}/submit", token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void adminEndpoints_requireAuth() throws Exception {
        mvc.perform(get("/das/onboarding/candidates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void funnelSummary_and_resendInvite() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        String inviteBody = mvc.perform(asJson(
                        post("/das/onboarding/invite").header("Authorization", bearer(admin)),
                        new DaOnboardingInviteRequest(email, "Fun", "El", null, "DEL")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String candidateId = json.readTree(inviteBody).get("candidateId").asText();
        String token = json.readTree(inviteBody).get("inviteToken").asText();

        // Funnel returns numeric stage counts.
        mvc.perform(get("/das/onboarding/funnel").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.draft").isNumber())
                .andExpect(jsonPath("$.conversionRate").isNumber());

        // Resend returns the same invite link while the candidate is still in progress.
        mvc.perform(post("/das/onboarding/candidates/{id}/resend-invite", candidateId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteToken").value(token));
    }

    @Test
    void invite_duplicateEmail_conflicts() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        mvc.perform(asJson(post("/das/onboarding/invite").header("Authorization", bearer(admin)),
                        new DaOnboardingInviteRequest(email, "Dup", "One", null, "DEL")))
                .andExpect(status().isCreated());
        mvc.perform(asJson(post("/das/onboarding/invite").header("Authorization", bearer(admin)),
                        new DaOnboardingInviteRequest(email, "Dup", "Two", null, "DEL")))
                .andExpect(status().isConflict());
    }
}
