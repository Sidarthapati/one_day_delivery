package com.oneday.orders.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.config.IdempotencyProperties;
import com.oneday.orders.domain.Address;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.repository.IdempotencyKeyRepository;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.PaymentPort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(B2cShipmentController.class)
@AutoConfigureMockMvc(addFilters = false)  // bypass IdempotencyFilter + Spring Security (M1 not yet wired)
@Import(B2cShipmentControllerTest.TestConfig.class)
class B2cShipmentControllerTest {

    // IdempotencyProperties is a concrete @Component class — Mockito inline mocks cannot
    // instrument it on Java 25. Provide a real bean with defaults via @TestConfiguration instead.
    @TestConfiguration
    static class TestConfig {
        @Bean
        IdempotencyProperties idempotencyProperties() {
            return new IdempotencyProperties();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean BookingService bookingService;
    // IdempotencyFilter is a @Component instantiated by @WebMvcTest; mock its repo dependency
    // so the context loads (addFilters=false prevents the filter from actually running).
    @MockBean IdempotencyKeyRepository idempotencyKeyRepository;

    private static final String IDEMPOTENCY_KEY = "idem-test-001";
    private static final String USER_ID         = "user-999";

    // ── 201 Created: happy path ────────────────────────────────────────────

    @Test
    void createShipment_success_returns201WithShipmentRef() throws Exception {
        when(bookingService.book(any(), anyString(), anyString())).thenReturn(happyResponse());

        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shipmentRef").value("1DD-BLR-20260530-00001"))
                .andExpect(jsonPath("$.state").value("BOOKED"))
                .andExpect(jsonPath("$.pricing.totalPricePaise").value(4720))
                .andExpect(jsonPath("$.labelStatus").value("PENDING"));
    }

    // ── 422: serviceability exception ──────────────────────────────────────

    @Test
    void createShipment_serviceabilityException_returns422() throws Exception {
        when(bookingService.book(any(), anyString(), anyString()))
                .thenThrow(new BookingService.ServiceabilityException("Route not serviceable"));

        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Route not serviceable"));
    }

    // ── 402: payment verification failure ──────────────────────────────────

    @Test
    void createShipment_paymentVerificationException_returns402() throws Exception {
        when(bookingService.book(any(), anyString(), anyString()))
                .thenThrow(new PaymentPort.PaymentVerificationException("Signature mismatch"));

        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Payment verification failed"));
    }

    // ── 503: circuit breaker open ──────────────────────────────────────────

    @Test
    void createShipment_circuitOpen_returns503() throws Exception {
        CircuitBreaker cb = CircuitBreaker.ofDefaults("test");
        when(bookingService.book(any(), anyString(), anyString()))
                .thenThrow(CallNotPermittedException.createCallNotPermittedException(cb));

        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingRequest())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service temporarily unavailable"));
    }

    // ── 422: @Valid constraint violation ──────────────────────────────────

    @Test
    void createShipment_invalidPhone_returns422WithViolations() throws Exception {
        BookingRequest req = bookingRequest();
        req.setSenderPhone("9876543210");  // missing +91 prefix — fails @Pattern

        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.violations.senderPhone").exists());
    }

    // ── 400: missing required header ──────────────────────────────────────

    @Test
    void createShipment_missingUserIdHeader_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        // X-User-Id deliberately omitted
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

        BookingResponse.PaymentSummary payment = new BookingResponse.PaymentSummary();
        payment.setMode(PaymentMode.PREPAID);
        payment.setStatus("CAPTURED");
        payment.setRazorpayPaymentId("pay_xyz789");

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
        resp.setPayment(payment);
        return resp;
    }

    private BookingRequest bookingRequest() {
        Address addr = new Address();
        addr.setLine1("123 Main St");
        addr.setCity("Bengaluru");
        addr.setPincode("560001");
        addr.setState("Karnataka");

        Address destAddr = new Address();
        destAddr.setLine1("456 Ring Rd");
        destAddr.setCity("Mumbai");
        destAddr.setPincode("400001");
        destAddr.setState("Maharashtra");

        BookingRequest req = new BookingRequest();
        req.setSenderName("Alice");
        req.setSenderPhone("+919876543210");
        req.setOriginAddress(addr);
        req.setOriginCity("BLR");
        req.setOriginPincode("560001");
        req.setReceiverName("Bob");
        req.setReceiverPhone("+919123456789");
        req.setDestAddress(destAddr);
        req.setDestCity("BOM");
        req.setDestPincode("400001");
        req.setWeightGrams(1000);
        req.setLengthCm((short) 20);
        req.setWidthCm((short) 15);
        req.setHeightCm((short) 10);
        req.setDeclaredValuePaise(50_000L);
        req.setPickupType(PickupType.DA_PICKUP);
        req.setDropType(DropType.DA_DELIVERY);
        req.setPaymentMode(PaymentMode.PREPAID);
        req.setRazorpayOrderId("order_abc123");
        req.setRazorpayPaymentId("pay_xyz789");
        req.setRazorpaySignature("sig_test");
        return req;
    }

    // ── 201 Created: COD happy path ────────────────────────────────────────

    @Test
    void createShipment_cod_returns201() throws Exception {
        BookingResponse codResponse = happyResponse();
        codResponse.getPayment().setMode(PaymentMode.COD);
        codResponse.getPayment().setStatus("COD_PENDING");
        codResponse.getPayment().setRazorpayPaymentId(null);
        when(bookingService.book(any(), anyString(), anyString())).thenReturn(codResponse);

        BookingRequest codReq = bookingRequest();
        codReq.setPaymentMode(PaymentMode.COD);
        codReq.setRazorpayOrderId(null);
        codReq.setRazorpayPaymentId(null);
        codReq.setRazorpaySignature(null);

        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(codReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.mode").value("COD"))
                .andExpect(jsonPath("$.payment.status").value("COD_PENDING"))
                .andExpect(jsonPath("$.payment.razorpayPaymentId").doesNotExist());
    }
}
