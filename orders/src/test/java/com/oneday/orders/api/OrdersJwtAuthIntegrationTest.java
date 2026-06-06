package com.oneday.orders.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.security.JwtAuthenticationFilter;
import com.oneday.auth.service.AuthService;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Address;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.service.BookingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration of the real M1 JWT path with the M4 booking controller:
 * the actual {@link JwtAuthenticationFilter} parses the {@code Authorization} header, builds
 * the {@code AuthUserDetails} principal, and the controller's role gate runs against it.
 * Only {@link AuthService#validateToken} is mocked (JWT crypto is M1's concern, tested there).
 */
@ExtendWith(MockitoExtension.class)
class OrdersJwtAuthIntegrationTest {

    @Mock private AuthService authService;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private BookingService bookingService;
    @Mock private com.oneday.orders.service.CancellationService cancellationService;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(authService, apiKeyRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(new B2cShipmentController(bookingService, cancellationService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new OrdersGlobalExceptionHandler())
                .addFilters(jwtFilter)
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validJwtForCustomer_booksThroughTheRealFilter() throws Exception {
        when(authService.validateToken("good-token"))
                .thenReturn(userWithRole("C2C_CUSTOMER", "00000000-0000-0000-0000-000000000009"));
        lenient().when(bookingService.book(any(), anyString(), anyString())).thenReturn(happyResponse());

        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer good-token")
                        .header("Idempotency-Key", "idem-jwt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isCreated());
    }

    @Test
    void validJwtForWrongRole_returns403() throws Exception {
        when(authService.validateToken("da-token"))
                .thenReturn(userWithRole("DELIVERY_ASSOCIATE", "00000000-0000-0000-0000-0000000000da"));

        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer da-token")
                        .header("Idempotency-Key", "idem-jwt-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", "idem-jwt-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static User userWithRole(String roleName, String userId) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setRole(role);
        user.setActive(true);
        ReflectionTestUtils.setField(user, "id", UUID.fromString(userId));
        return user;
    }

    private BookingResponse happyResponse() {
        BookingResponse r = new BookingResponse();
        r.setShipmentRef("1DD-DEL-20260606-00001");
        r.setState(ShipmentState.BOOKED);
        return r;
    }

    private BookingRequest validRequest() {
        Address origin = new Address();
        origin.setLine1("1 CP"); origin.setCity("Delhi"); origin.setPincode("110001"); origin.setState("DL");
        Address dest = new Address();
        dest.setLine1("1 MG"); dest.setCity("Bengaluru"); dest.setPincode("560001"); dest.setState("KA");

        BookingRequest req = new BookingRequest();
        req.setSenderName("Ravi"); req.setSenderPhone("+919000000001");
        req.setOriginAddress(origin); req.setOriginCity("DEL"); req.setOriginPincode("110001");
        req.setReceiverName("Priya"); req.setReceiverPhone("+919000000002");
        req.setDestAddress(dest); req.setDestCity("BLR"); req.setDestPincode("560001");
        req.setWeightGrams(1000); req.setLengthCm((short) 20); req.setWidthCm((short) 15); req.setHeightCm((short) 10);
        req.setPickupType(PickupType.DA_PICKUP); req.setDropType(DropType.DA_DELIVERY);
        req.setPaymentMode(PaymentMode.COD);
        return req;
    }
}
