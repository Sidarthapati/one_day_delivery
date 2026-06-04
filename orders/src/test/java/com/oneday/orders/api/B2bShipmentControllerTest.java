package com.oneday.orders.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.config.IdempotencyProperties;
import com.oneday.orders.domain.Address;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.repository.IdempotencyKeyRepository;
import com.oneday.orders.service.B2bBookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(B2bShipmentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(B2bShipmentControllerTest.TestConfig.class)
class B2bShipmentControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        IdempotencyProperties idempotencyProperties() {
            return new IdempotencyProperties();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean B2bBookingService b2bBookingService;
    @MockBean IdempotencyKeyRepository idempotencyKeyRepository;

    private static final String IDEMPOTENCY_KEY = "idem-b2b-001";
    private static final String USER_ID         = "user-b2b-999";
    private static final UUID   ACCOUNT_ID      = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // ── 201 Created: happy path ────────────────────────────────────────────

    @Test
    void createB2bShipment_success_returns201WithoutPaymentBlock() throws Exception {
        when(b2bBookingService.book(any(), anyString(), anyString())).thenReturn(happyResponse());

        mockMvc.perform(post("/api/v1/b2b/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shipmentRef").value("1DD-BLR-20260530-00001"))
                .andExpect(jsonPath("$.state").value("BOOKED"))
                .andExpect(jsonPath("$.pricing.totalPricePaise").value(4720))
                .andExpect(jsonPath("$.labelStatus").value("PENDING"))
                .andExpect(jsonPath("$.payment").doesNotExist());  // B2B has no payment block
    }

    // ── 402: credit limit exceeded ─────────────────────────────────────────

    @Test
    void createB2bShipment_creditLimitExceeded_returns402() throws Exception {
        when(b2bBookingService.book(any(), anyString(), anyString()))
                .thenThrow(new B2bBookingService.CreditLimitExceededException("Credit limit exceeded"));

        mockMvc.perform(post("/api/v1/b2b/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Credit limit exceeded"));
    }

    // ── 409: account inactive ──────────────────────────────────────────────

    @Test
    void createB2bShipment_accountInactive_returns409() throws Exception {
        when(b2bBookingService.book(any(), anyString(), anyString()))
                .thenThrow(new B2bBookingService.AccountInactiveException("B2B account is inactive"));

        mockMvc.perform(post("/api/v1/b2b/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("B2B account inactive"));
    }

    // ── 404: account not found ─────────────────────────────────────────────

    @Test
    void createB2bShipment_accountNotFound_returns404() throws Exception {
        when(b2bBookingService.book(any(), anyString(), anyString()))
                .thenThrow(new B2bBookingService.AccountNotFoundException("B2B account not found"));

        mockMvc.perform(post("/api/v1/b2b/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("B2B account not found"));
    }

    // ── 422: Bean Validation — missing b2bAccountId ────────────────────────

    @Test
    void createB2bShipment_missingB2bAccountId_returns422() throws Exception {
        B2bBookingRequest req = bookingRequest();
        req.setB2bAccountId(null);

        mockMvc.perform(post("/api/v1/b2b/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.violations.b2bAccountId").exists());
    }

    // ── 400: missing Idempotency-Key header ───────────────────────────────

    @Test
    void createB2bShipment_missingIdempotencyKeyHeader_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/b2b/shipments")
                        // Idempotency-Key deliberately omitted
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Missing required header"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private BookingResponse happyResponse() {
        BookingResponse.PricingDetails pricing = new BookingResponse.PricingDetails();
        pricing.setQuotedPricePaise(4000L);
        pricing.setGstPaise(720L);
        pricing.setTotalPricePaise(4720L);
        pricing.setCurrency("INR");
        pricing.setBreakdown(Map.of("base_freight", 4000L));
        pricing.setRateCardVersion("v1");

        BookingResponse resp = new BookingResponse();
        resp.setShipmentRef("1DD-BLR-20260530-00001");
        resp.setState(ShipmentState.BOOKED);
        resp.setStateLabel("Order confirmed");
        resp.setDeliveryType(DeliveryType.INTERCITY);
        resp.setPricing(pricing);
        resp.setEtaPromised(Instant.now().plusSeconds(86400));
        resp.setSlaCommitmentMinutes(1440);
        resp.setTrackingUrl("/api/v1/shipments/1DD-BLR-20260530-00001/track");
        resp.setLabelStatus("PENDING");
        resp.setPayment(null);  // B2B: no payment block
        return resp;
    }

    private B2bBookingRequest bookingRequest() {
        Address origin = new Address();
        origin.setLine1("123 Main St");
        origin.setCity("Bengaluru");
        origin.setPincode("560001");
        origin.setState("Karnataka");

        Address dest = new Address();
        dest.setLine1("456 Ring Rd");
        dest.setCity("Mumbai");
        dest.setPincode("400001");
        dest.setState("Maharashtra");

        B2bBookingRequest req = new B2bBookingRequest();
        req.setB2bAccountId(ACCOUNT_ID);
        req.setPurchaseOrderRef("PO-2026-001");
        req.setSenderName("Alice");
        req.setSenderPhone("+919876543210");
        req.setOriginAddress(origin);
        req.setOriginCity("BLR");
        req.setOriginPincode("560001");
        req.setReceiverName("Bob");
        req.setReceiverPhone("+919123456789");
        req.setDestAddress(dest);
        req.setDestCity("BOM");
        req.setDestPincode("400001");
        req.setWeightGrams(1000);
        req.setLengthCm((short) 20);
        req.setWidthCm((short) 15);
        req.setHeightCm((short) 10);
        req.setDeclaredValuePaise(50_000L);
        req.setPickupType(PickupType.DA_PICKUP);
        req.setDropType(DropType.DA_DELIVERY);
        return req;
    }
}
