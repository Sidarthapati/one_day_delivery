package com.oneday.orders.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.service.AuthService;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.port.EtaPort;
import com.oneday.common.port.PricingPort;
import com.oneday.common.port.ServiceabilityPort;
import com.oneday.common.port.dto.EtaResult;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.common.port.dto.ServiceabilityResult;
import com.oneday.orders.domain.Address;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for the M4 full-stack end-to-end tests. Each test drives a real HTTP request
 * through the real M1 JWT filter → controller → service → state machine → <b>real Postgres</b>,
 * with only the cross-module/external ports mocked (M3 grid serviceability, M2 pricing, M9 ETA,
 * Razorpay payments, JWT crypto, Kafka publisher). Kafka auto-config is excluded so no broker is
 * needed. Tests are {@code @Transactional} → every booking is rolled back, keeping the shared dev
 * DB clean and each scenario isolated.
 */
// Tagged "e2e" (inherited by every subclass) so CI can exclude these real-Postgres
// full-stack tests with -DexcludedGroups=e2e; they still run locally against the dev DB.
@Tag("e2e")
@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(E2eSecurityConfig.class)
@Transactional
abstract class OrdersE2eSupport {

    // Demo B2B account seeded by V4_13 (owner = DEMO_OWNER per V4_16; ₹50k limit, ₹12k outstanding).
    protected static final String B2B_ACCOUNT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    protected static final String B2B_OWNER_USER_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected ShipmentStateMachine stateMachine;
    @Autowired protected PickupOtpService pickupOtpService;
    @Autowired protected ShipmentRepository shipmentRepository;

    @MockBean protected ServiceabilityPort serviceabilityPort;
    @MockBean protected PricingPort pricingPort;
    @MockBean protected EtaPort etaPort;
    @MockBean protected PaymentPort paymentPort;
    @MockBean protected AuthService authService;
    @MockBean protected ApiKeyRepository apiKeyRepository;
    @MockBean protected EventPublisher eventPublisher;

    /** Default happy-path stubs for the external ports; individual tests override as needed. */
    @BeforeEach
    void baseStubs() {
        // M3: serviceable INTERCITY route (origin/dest tiles present).
        lenient().when(serviceabilityPort.check(any())).thenReturn(
                new ServiceabilityResult(true, UUID.randomUUID(), UUID.randomUUID(), DeliveryType.INTERCITY));
        // M2: a fixed quote (₹40 freight + ₹7.20 GST = ₹47.20).
        lenient().when(pricingPort.computeQuote(any())).thenReturn(
                new QuoteResult(4000L, 720L, 4720L, Map.of("base_freight", 4000L), "v1"));
        // M9: an ETA so the response is fully populated.
        lenient().when(etaPort.fetchEta(any())).thenReturn(
                new EtaResult(Instant.now().plusSeconds(86_400), 1440));
        // Razorpay: signature/capture/refund all succeed by default (PREPAID happy path).
        lenient().when(paymentPort.initiateRefund(anyString(), anyLong())).thenReturn("rfnd_test_1");
    }

    // ── Auth helpers ────────────────────────────────────────────────────────

    /**
     * Registers a JWT principal: a request carrying {@code Authorization: Bearer <returned token>}
     * is authenticated by the real filter as a user with the given role + id.
     */
    protected String tokenFor(String role, String userId) {
        return tokenForCity(role, userId, null);
    }

    /** Like {@link #tokenFor} but also stamps the principal's city (for station-manager scope). */
    protected String tokenForCity(String role, String userId, String cityId) {
        String token = "tok:" + role + ":" + userId + ":" + cityId;
        Role r = new Role();
        r.setName(role);
        User u = new User();
        u.setRole(r);
        u.setActive(true);
        u.setCityId(cityId);
        ReflectionTestUtils.setField(u, "id", UUID.fromString(userId));
        lenient().when(authService.validateToken(token)).thenReturn(u);
        return token;
    }

    // ── Request builders ──────────────────────────────────────────────────────

    protected BookingRequest b2cRequest(PaymentMode mode) {
        BookingRequest req = new BookingRequest();
        req.setSenderName("Ravi Sender");
        req.setSenderPhone("+919000000001");
        req.setOriginAddress(addr("1 Connaught Place", "Delhi", "110001", "DL"));
        req.setOriginCity("DEL");
        req.setOriginPincode("110001");
        req.setReceiverName("Priya Receiver");
        req.setReceiverPhone("+919000000002");
        req.setDestAddress(addr("1 MG Road", "Bengaluru", "560001", "KA"));
        req.setDestCity("BLR");
        req.setDestPincode("560001");
        req.setWeightGrams(1000);
        req.setLengthCm((short) 20);
        req.setWidthCm((short) 15);
        req.setHeightCm((short) 10);
        req.setPickupType(PickupType.DA_PICKUP);
        req.setDropType(DropType.DA_DELIVERY);
        req.setPaymentMode(mode);
        if (mode == PaymentMode.PREPAID) {
            req.setRazorpayOrderId("order_test_1");
            req.setRazorpayPaymentId("pay_test_1");
            req.setRazorpaySignature("sig_test_1");
        }
        return req;
    }

    protected B2bBookingRequest b2bRequest() {
        B2bBookingRequest req = new B2bBookingRequest();
        req.setB2bAccountId(UUID.fromString(B2B_ACCOUNT_ID));
        req.setSenderName("Acme Warehouse");
        req.setSenderPhone("+919000000010");
        req.setOriginAddress(addr("Plot 5 Whitefield", "Bengaluru", "560001", "KA"));
        req.setOriginCity("BLR");
        req.setOriginPincode("560001");
        req.setReceiverName("Client Dock");
        req.setReceiverPhone("+919000000011");
        req.setDestAddress(addr("1 Connaught Place", "Delhi", "110001", "DL"));
        req.setDestCity("DEL");
        req.setDestPincode("110001");
        req.setWeightGrams(2000);
        req.setLengthCm((short) 30);
        req.setWidthCm((short) 25);
        req.setHeightCm((short) 20);
        req.setDeclaredValuePaise(500000L);
        req.setPickupType(PickupType.DA_PICKUP);
        req.setDropType(DropType.DA_DELIVERY);
        return req;
    }

    protected static Address addr(String line1, String city, String pincode, String state) {
        Address a = new Address();
        a.setLine1(line1);
        a.setCity(city);
        a.setPincode(pincode);
        a.setState(state);
        return a;
    }

    /** Random idempotency key — every booking call needs a fresh one. */
    protected static String idemKey() {
        return "idem-" + UUID.randomUUID();
    }

    protected static String randomUserId() {
        return UUID.randomUUID().toString();
    }

    // ── Booking / state-driving helpers (used by cancel & OTP journeys) ─────────

    /** Books a B2C shipment over HTTP as the given customer and returns its shipment_ref. */
    protected String bookB2c(String token, PaymentMode mode) throws Exception {
        String body = mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(mode))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("shipment_ref").asText();
    }

    protected UUID idOf(String shipmentRef) {
        return shipmentRepository.findByShipmentRef(shipmentRef).orElseThrow().getId();
    }

    /** Advances a shipment through the given states via the real state machine (simulates M5–M9). */
    protected void drive(String shipmentRef, ShipmentState... targets) {
        UUID id = idOf(shipmentRef);
        TransitionContext ctx = TransitionContext.fromApi(B2B_OWNER_USER_ID, "e2e-drive");
        for (ShipmentState target : targets) {
            stateMachine.transition(id, target, ctx);
        }
    }
}
