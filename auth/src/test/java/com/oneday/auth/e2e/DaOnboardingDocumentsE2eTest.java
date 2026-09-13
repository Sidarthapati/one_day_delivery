package com.oneday.auth.e2e;

import com.oneday.auth.dto.request.DaOnboardingInviteRequest;
import com.oneday.auth.dto.request.DocumentUploadUrlRequest;
import com.oneday.auth.dto.request.SubmitDocumentsRequest;
import com.oneday.auth.domain.OnboardingDocType;
import com.oneday.common.port.ObjectStoragePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E · onboarding document upload (slice S2). The auth-only context has no R2 adapter, so an in-memory
 * {@link ObjectStoragePort} is supplied here — the presign → submit → list flow runs end to end through
 * the real controllers. The assembled app uses the real R2 adapter instead (verified in the final E2E).
 */
@DisplayName("E2E · DA onboarding documents")
@Import(DaOnboardingDocumentsE2eTest.StorageTestConfig.class)
class DaOnboardingDocumentsE2eTest extends AuthE2eSupport {

    @Test
    void presignSubmitAndReview_documents() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        String inviteBody = mvc.perform(asJson(
                        post("/das/onboarding/invite").header("Authorization", bearer(admin)),
                        new DaOnboardingInviteRequest(email, "Doc", "Person", null, "DEL")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(inviteBody).get("inviteToken").asText();
        String candidateId = json.readTree(inviteBody).get("candidateId").asText();

        // Presign two upload slots.
        String slotsBody = mvc.perform(asJson(post("/public/onboarding/{t}/documents/upload-urls", token),
                        new DocumentUploadUrlRequest(List.of(
                                new DocumentUploadUrlRequest.Item(OnboardingDocType.AADHAAR_FRONT, null),
                                new DocumentUploadUrlRequest.Item(OnboardingDocType.PAN, "application/pdf")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uploadUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        var slots = json.readTree(slotsBody);
        String aadhaarKey = slots.get(0).get("objectKey").asText();
        String panKey = slots.get(1).get("objectKey").asText();

        // (Applicant would PUT the files to uploadUrl here — the in-memory store marks presigned keys present.)

        // Submit the keys.
        mvc.perform(asJson(post("/public/onboarding/{t}/documents", token),
                        new SubmitDocumentsRequest(List.of(
                                new SubmitDocumentsRequest.Item(OnboardingDocType.AADHAAR_FRONT, aadhaarKey),
                                new SubmitDocumentsRequest.Item(OnboardingDocType.PAN, panKey)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("UPLOADED"));

        // Applicant can list their own docs.
        mvc.perform(get("/public/onboarding/{t}/documents", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Reviewer sees them with presigned download URLs, then verifies one.
        String reviewBody = mvc.perform(get("/das/onboarding/candidates/{id}/documents", candidateId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].downloadUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String docId = json.readTree(reviewBody).get(0).get("id").asText();

        mvc.perform(post("/das/onboarding/candidates/{id}/documents/{docId}/status", candidateId, docId)
                        .param("status", "VERIFIED").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @TestConfiguration
    static class StorageTestConfig {
        /** In-memory object store: presigned keys are treated as present so submit() validation passes. */
        @Bean
        ObjectStoragePort inMemoryObjectStorage() {
            return new ObjectStoragePort() {
                private final Set<String> keys = ConcurrentHashMap.newKeySet();

                @Override public boolean isAvailable() { return true; }

                @Override public String presignPut(String key, String contentType, Duration ttl) {
                    keys.add(key);
                    return "https://mock-r2/put/" + key;
                }

                @Override public String presignGet(String key, Duration ttl) {
                    return "https://mock-r2/get/" + key;
                }

                @Override public boolean exists(String key) { return keys.contains(key); }

                @Override public long size(String key) { return keys.contains(key) ? 1L : -1L; }

                @Override public byte[] getBytes(String key) { return new byte[0]; }
            };
        }
    }
}
