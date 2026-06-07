package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.repository.B2bAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Cancellation")
class CancellationE2eTest extends OrdersE2eSupport {

    @Autowired B2bAccountRepository b2bAccountRepository;

    // A customer cancels a fresh PREPAID booking: a Razorpay refund is initiated and the shipment
    // moves to CANCELLED.
    @Test
    void cancelPrepaid_initiatesRefundAndCancels() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);

        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk());

        verify(paymentPort).initiateRefund(eq("pay_test_1"), anyLong());
        assertThat(shipmentRepository.findByShipmentRef(ref).orElseThrow().getState())
                .isEqualTo(ShipmentState.CANCELLED);
    }

    // Cancelling a COD booking needs no refund — it just transitions to CANCELLED.
    @Test
    void cancelCod_cancelsWithoutRefund() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.COD);

        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    // Past the cancellation cutoff (parcel already on the pickup van) the request is refused with 409.
    @Test
    void cancelPastCutoff_returns409() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.COD);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN);

        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isConflict());
    }

    // Lane guard: a retail customer cannot cancel a B2B shipment via the B2C endpoint → 404.
    @Test
    void b2cCallerCancellingB2bShipment_returns404() throws Exception {
        // Book a B2B shipment, then try to cancel it on the b2c lane as a retail customer.
        String b2bToken = tokenFor("B2B_USER", B2B_OWNER_USER_ID);
        String b2bBody = mvc.perform(post("/api/v1/b2b/shipments")
                        .header("Authorization", "Bearer " + b2bToken)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2bRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String b2bRef = json.readTree(b2bBody).get("shipment_ref").asText();

        String retailToken = tokenFor("B2C_CUSTOMER", randomUserId());
        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", b2bRef)
                        .header("Authorization", "Bearer " + retailToken)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isNotFound());
    }

    // A B2B credit cancellation reverses the account's outstanding balance back to its prior value.
    @Test
    void cancelB2b_reversesOutstandingBalance() throws Exception {
        UUID accountId = UUID.fromString(B2B_ACCOUNT_ID);
        long before = b2bAccountRepository.findById(accountId).orElseThrow().getOutstandingBalancePaise();
        String token = tokenFor("B2B_USER", B2B_OWNER_USER_ID);

        String body = mvc.perform(post("/api/v1/b2b/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2bRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String ref = json.readTree(body).get("shipment_ref").asText();

        mvc.perform(delete("/api/v1/b2b/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk());

        long after = b2bAccountRepository.findById(accountId).orElseThrow().getOutstandingBalancePaise();
        assertThat(after).isEqualTo(before);
    }
}
