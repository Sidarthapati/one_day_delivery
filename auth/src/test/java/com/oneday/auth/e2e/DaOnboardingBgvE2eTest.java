package com.oneday.auth.e2e;

import com.oneday.auth.dto.request.CandidateLocationRequest;
import com.oneday.auth.dto.request.CandidatePersonalRequest;
import com.oneday.auth.dto.request.DaOnboardingInviteRequest;
import com.oneday.common.domain.Shift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E · onboarding BGV (slice S3). Exercises the mock vendor's adverse path: a candidate flagged "FAIL"
 * resolves RED across all checks and still lands in the reviewer queue (INSUFFICIENT), where the per-check
 * verdicts inform a reject.
 */
@DisplayName("E2E · DA onboarding BGV")
class DaOnboardingBgvE2eTest extends AuthE2eSupport {

    @Test
    void bgvAdversePath_resolvesRed_thenReviewerRejects() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        String inviteBody = mvc.perform(asJson(
                        post("/das/onboarding/invite").header("Authorization", bearer(admin)),
                        new DaOnboardingInviteRequest(email, "FAIL", "Applicant", null, "DEL")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(inviteBody).get("inviteToken").asText();
        String candidateId = json.readTree(inviteBody).get("candidateId").asText();

        // "FAIL" in the name drives the mock vendor to an adverse verdict.
        mvc.perform(asJson(put("/public/onboarding/{t}/personal", token),
                        new CandidatePersonalRequest("FAIL", "Applicant", null, null, "111122223333", "ABCDE1234F", null)))
                .andExpect(status().isOk());
        mvc.perform(asJson(put("/public/onboarding/{t}/location", token),
                        new CandidateLocationRequest("DEL", null, Shift.SHIFT_1)))
                .andExpect(status().isOk());
        mvc.perform(post("/public/onboarding/{t}/agreement/accept", token)).andExpect(status().isOk());
        mvc.perform(post("/public/onboarding/{t}/training/ack", token)).andExpect(status().isOk());
        uploadAllRequiredDocs(candidateId);   // mandatory documents present
        mvc.perform(post("/public/onboarding/{t}/submit", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BGV_IN_PROGRESS"));

        // Refresh resolves every check RED; the candidate still enters the queue for a human decision.
        mvc.perform(post("/das/onboarding/candidates/{id}/bgv/refresh", candidateId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RED"));
        mvc.perform(get("/das/onboarding/candidates/{id}", candidateId).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Reviewer rejects on the adverse BGV.
        mvc.perform(post("/das/onboarding/candidates/{id}/reject", candidateId)
                        .header("Authorization", bearer(admin))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Adverse BGV\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
