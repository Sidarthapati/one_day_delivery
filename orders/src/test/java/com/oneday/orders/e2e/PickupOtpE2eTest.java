package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ShipmentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Pickup OTP")
class PickupOtpE2eTest extends OrdersE2eSupport {

    private static final String DA = "DELIVERY_ASSOCIATE";

    // The delivery associate collects the parcel: verifying the sender's OTP transitions the
    // shipment PICKUP_ASSIGNED → PICKED_UP.
    @Test
    void verifyOtp_transitionsToPickedUp() throws Exception {
        String ref = bookB2c(tokenFor("B2C_CUSTOMER", randomUserId()), PaymentMode.COD);
        drive(ref, ShipmentState.PICKUP_ASSIGNED);
        String otp = pickupOtpService.generate(idOf(ref));   // the SMS the sender would receive

        mvc.perform(post("/internal/v1/shipments/{ref}/pickup-otp/verify", ref)
                        .header("Authorization", "Bearer " + tokenFor(DA, randomUserId()))
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("otp", otp))))
                .andExpect(status().isNoContent());

        assertThat(shipmentRepository.findByShipmentRef(ref).orElseThrow().getState())
                .isEqualTo(ShipmentState.PICKED_UP);
    }

    // A wrong OTP is rejected with 422 and the shipment stays in PICKUP_ASSIGNED.
    @Test
    void verifyWrongOtp_returns422() throws Exception {
        String ref = bookB2c(tokenFor("B2C_CUSTOMER", randomUserId()), PaymentMode.COD);
        drive(ref, ShipmentState.PICKUP_ASSIGNED);
        String real = pickupOtpService.generate(idOf(ref));
        String wrong = real.equals("000000") ? "111111" : "000000";

        mvc.perform(post("/internal/v1/shipments/{ref}/pickup-otp/verify", ref)
                        .header("Authorization", "Bearer " + tokenFor(DA, randomUserId()))
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("otp", wrong))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(shipmentRepository.findByShipmentRef(ref).orElseThrow().getState())
                .isEqualTo(ShipmentState.PICKUP_ASSIGNED);
    }

    // The DA can request a fresh OTP when the sender didn't receive the first one.
    @Test
    void resendOtp_returnsNewCode() throws Exception {
        String ref = bookB2c(tokenFor("B2C_CUSTOMER", randomUserId()), PaymentMode.COD);
        drive(ref, ShipmentState.PICKUP_ASSIGNED);
        pickupOtpService.generate(idOf(ref));

        mvc.perform(post("/internal/v1/shipments/{ref}/pickup-otp/resend", ref)
                        .header("Authorization", "Bearer " + tokenFor(DA, randomUserId()))
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otp").exists());
    }

    // Only a delivery associate may verify a pickup OTP — a customer is refused with 403.
    @Test
    void verifyWrongRole_returns403() throws Exception {
        String ref = bookB2c(tokenFor("B2C_CUSTOMER", randomUserId()), PaymentMode.COD);
        drive(ref, ShipmentState.PICKUP_ASSIGNED);

        mvc.perform(post("/internal/v1/shipments/{ref}/pickup-otp/verify", ref)
                        .header("Authorization", "Bearer " + tokenFor("B2C_CUSTOMER", randomUserId()))
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("otp", "1234"))))
                .andExpect(status().isForbidden());
    }
}
