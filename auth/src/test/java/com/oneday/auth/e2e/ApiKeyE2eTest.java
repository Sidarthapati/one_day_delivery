package com.oneday.auth.e2e;

import com.oneday.auth.dto.request.ApiKeyCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · API keys")
class ApiKeyE2eTest extends AuthE2eSupport {

    // A customer mints an API key and receives the raw secret exactly once.
    @Test
    void createApiKey_returnsRawKeyOnce() throws Exception {
        String token = tokenForRole("B2C_CUSTOMER");

        mvc.perform(asJson(post("/auth/api-keys").header("Authorization", bearer(token)),
                        new ApiKeyCreateRequest("integration-key")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("integration-key"))
                .andExpect(jsonPath("$.rawKey").isNotEmpty());
    }

    // The raw API key authenticates subsequent requests via the X-Api-Key header (no JWT needed).
    @Test
    void apiKey_authenticatesViaXApiKeyHeader() throws Exception {
        String token = tokenForRole("B2C_CUSTOMER");
        String rawKey = createKey(token, "machine-key");

        mvc.perform(get("/roles").header("X-Api-Key", rawKey))
                .andExpect(status().isOk());
    }

    // Revoking an API key immediately stops it from authenticating → 401.
    @Test
    void revokedApiKey_isRejected() throws Exception {
        String token = tokenForRole("B2C_CUSTOMER");
        String body = mvc.perform(asJson(post("/auth/api-keys").header("Authorization", bearer(token)),
                        new ApiKeyCreateRequest("to-revoke")))
                .andReturn().getResponse().getContentAsString();
        String keyId = json.readTree(body).get("id").asText();
        String rawKey = json.readTree(body).get("rawKey").asText();

        mvc.perform(delete("/auth/api-keys/{id}", keyId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/roles").header("X-Api-Key", rawKey))
                .andExpect(status().isUnauthorized());
    }

    // Each user is capped at 10 active API keys; the 11th is refused with 422.
    @Test
    void apiKeyCap_isEnforced() throws Exception {
        String token = tokenForRole("B2C_CUSTOMER");
        for (int i = 0; i < 10; i++) {
            createKey(token, "key-" + i);
        }
        mvc.perform(asJson(post("/auth/api-keys").header("Authorization", bearer(token)),
                        new ApiKeyCreateRequest("one-too-many")))
                .andExpect(status().isUnprocessableEntity());
    }

    // API keys are only for customer/admin accounts — an operational role cannot mint one → 403.
    @Test
    void operationalRole_cannotCreateApiKey() throws Exception {
        String token = tokenForRole("DELIVERY_ASSOCIATE");
        mvc.perform(asJson(post("/auth/api-keys").header("Authorization", bearer(token)),
                        new ApiKeyCreateRequest("nope")))
                .andExpect(status().isForbidden());
    }

    private String createKey(String token, String label) throws Exception {
        String body = mvc.perform(asJson(post("/auth/api-keys").header("Authorization", bearer(token)),
                        new ApiKeyCreateRequest(label)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("rawKey").asText();
    }
}
