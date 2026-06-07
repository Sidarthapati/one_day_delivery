package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.orders.dto.BookingRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Idempotency")
class IdempotencyE2eTest extends OrdersE2eSupport {

    // Retrying the exact same booking with the same Idempotency-Key replays the original response
    // (same shipment_ref, marked replayed) instead of creating a second shipment.
    @Test
    void sameKeySameBody_replaysOriginalResponse() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String key = idemKey();
        String body = json.writeValueAsString(b2cRequest(PaymentMode.COD));

        String first = mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String ref = json.readTree(first).get("shipment_ref").asText();

        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.shipment_ref").value(ref));
    }

    // Reusing a key with a *different* body is a client error (422) — the key is bound to its
    // original request fingerprint.
    @Test
    void sameKeyDifferentBody_returns422() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String key = idemKey();

        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.COD))))
                .andExpect(status().isCreated());

        BookingRequest changed = b2cRequest(PaymentMode.COD);
        changed.setWeightGrams(9999);   // different fingerprint, same key
        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(json.writeValueAsString(changed)))
                .andExpect(status().isUnprocessableEntity());
    }

    // A POST without an Idempotency-Key is rejected before reaching the handler → 400.
    @Test
    void missingKey_returns400() throws Exception {
        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + tokenFor("B2C_CUSTOMER", randomUserId()))
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.COD))))
                .andExpect(status().isBadRequest());
    }
}
