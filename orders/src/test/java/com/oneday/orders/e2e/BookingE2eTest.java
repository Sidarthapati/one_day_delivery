package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.common.port.dto.ServiceabilityResult;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.PaymentPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Booking (B2C / C2C / B2B)")
class BookingE2eTest extends OrdersE2eSupport {

    // shipmentRepository is inherited from OrdersE2eSupport.
    @Autowired B2bAccountRepository b2bAccountRepository;

    // A B2C customer pays online (PREPAID): real Razorpay verify+capture runs, the shipment is
    // persisted as BOOKED and the response carries the captured payment summary.
    @Test
    void b2cPrepaid_capturesPaymentAndPersistsBookedShipment() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());

        String body = mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.PREPAID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer_type").value("B2C"))
                .andExpect(jsonPath("$.state").value("BOOKED"))
                .andExpect(jsonPath("$.payment.mode").value("PREPAID"))
                .andExpect(jsonPath("$.pricing.total_price_paise").value(4720))
                .andReturn().getResponse().getContentAsString();

        String ref = json.readTree(body).get("shipment_ref").asText();
        verify(paymentPort).capture(eq("pay_test_1"), eq(4720L));
        assertThat(shipmentRepository.findByShipmentRef(ref)).isPresent();
    }

    // A B2C customer pays cash on delivery (COD): no gateway interaction, the shipment books and
    // the payment summary mode is COD.
    @Test
    void b2cCod_booksWithoutGateway() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());

        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.COD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.mode").value("COD"))
                .andExpect(jsonPath("$.shipment_ref").exists());
    }

    // A peer-to-peer (C2C) customer books on the same retail endpoint: the persisted customer_type
    // reflects the real role, not the lane.
    @Test
    void c2cCustomer_bookedAsC2cNotB2c() throws Exception {
        String token = tokenFor("C2C_CUSTOMER", randomUserId());

        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.COD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer_type").value("C2C"));
    }

    // M3 reports the route is outside the grid → booking is rejected with 422 and nothing persists.
    @Test
    void nonServiceableRoute_returns422() throws Exception {
        when(serviceabilityPort.check(any())).thenReturn(
                new ServiceabilityResult(false, null, null, DeliveryType.INTERCITY));
        String token = tokenFor("B2C_CUSTOMER", randomUserId());

        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.COD))))
                .andExpect(status().isUnprocessableEntity());
    }

    // PREPAID booking that omits the Razorpay proof fields is rejected before any gateway call.
    @Test
    void prepaidWithoutPaymentProof_returns422() throws Exception {
        BookingRequest req = b2cRequest(PaymentMode.PREPAID);
        req.setRazorpayOrderId(null);
        req.setRazorpayPaymentId(null);
        req.setRazorpaySignature(null);
        String token = tokenFor("B2C_CUSTOMER", randomUserId());

        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity());
    }

    // A tampered Razorpay signature fails HMAC verification → 402 Payment Required, no shipment.
    @Test
    void tamperedSignature_returns402() throws Exception {
        org.mockito.Mockito.doThrow(new PaymentPort.PaymentVerificationException("Signature mismatch"))
                .when(paymentPort).verifySignature(any(), any(), any());
        String token = tokenFor("B2C_CUSTOMER", randomUserId());

        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.PREPAID))))
                .andExpect(status().isPaymentRequired());
    }

    // A B2B account user books on credit: customer_type is B2B, no payment summary is returned,
    // and the account's outstanding balance is incremented by the quote total.
    @Test
    void b2bOnCredit_incrementsOutstandingBalance() throws Exception {
        UUID accountId = UUID.fromString(B2B_ACCOUNT_ID);
        long before = b2bAccountRepository.findById(accountId).orElseThrow().getOutstandingBalancePaise();
        String token = tokenFor("B2B_USER", B2B_OWNER_USER_ID);

        mvc.perform(post("/api/v1/b2b/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2bRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer_type").value("B2B"))
                .andExpect(jsonPath("$.payment").doesNotExist());

        long after = b2bAccountRepository.findById(accountId).orElseThrow().getOutstandingBalancePaise();
        assertThat(after).isEqualTo(before + 4720L);
    }

    // A B2B booking whose quote would push outstanding past the credit limit is refused with 402.
    @Test
    void b2bOverCreditLimit_returns402() throws Exception {
        when(pricingPort.computeQuote(any())).thenReturn(
                new QuoteResult(9_000_000L, 0L, 9_000_000L, Map.of("base_freight", 9_000_000L), "v1"));
        String token = tokenFor("B2B_USER", B2B_OWNER_USER_ID);

        mvc.perform(post("/api/v1/b2b/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2bRequest())))
                .andExpect(status().isPaymentRequired());
    }

    // A B2B_USER who is not the account owner cannot book against that account → 403.
    @Test
    void b2bNonOwner_returns403() throws Exception {
        String token = tokenFor("B2B_USER", randomUserId());   // not the seeded owner

        mvc.perform(post("/api/v1/b2b/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2bRequest())))
                .andExpect(status().isForbidden());
    }

    // Booking against a non-existent B2B account → 404.
    @Test
    void b2bUnknownAccount_returns404() throws Exception {
        B2bBookingRequest req = b2bRequest();
        req.setB2bAccountId(UUID.randomUUID());
        String token = tokenFor("B2B_USER", B2B_OWNER_USER_ID);

        mvc.perform(post("/api/v1/b2b/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }
}
