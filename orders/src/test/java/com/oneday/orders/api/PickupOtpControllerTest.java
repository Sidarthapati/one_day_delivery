package com.oneday.orders.api;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.OtpVerifyRequest;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickupOtpControllerTest {

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private PickupOtpService pickupOtpService;
    @Mock private ShipmentStateMachine stateMachine;

    private PickupOtpController controller;

    private static final String REF = "1DD-BLR-20260530-00001";
    private static final UUID SHIPMENT_ID = UUID.randomUUID();

    // Authenticated DA principal — the controller now derives the actor + gates on role.
    private AuthUserDetails da;

    @BeforeEach
    void setUp() {
        controller = new PickupOtpController(shipmentRepository, pickupOtpService, stateMachine);
        da = principalWithRole("DELIVERY_ASSOCIATE", "00000000-0000-0000-0000-0000000000da");
    }

    private static AuthUserDetails principalWithRole(String roleName, String userId) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setRole(role);
        ReflectionTestUtils.setField(user, "id", UUID.fromString(userId));
        return new AuthUserDetails(user);
    }

    // -------------------------------------------------------------------------
    // verifyOtp
    // -------------------------------------------------------------------------

    @Test
    void verifyOtp_correctOtp_returns204AndTransitionsState() {
        when(shipmentRepository.findByShipmentRef(REF))
                .thenReturn(Optional.of(shipment(ShipmentState.PICKUP_ASSIGNED)));
        doNothing().when(pickupOtpService).verify(eq(SHIPMENT_ID), any());
        doNothing().when(stateMachine).transition(eq(SHIPMENT_ID),
                eq(ShipmentState.PICKED_UP), any());

        ResponseEntity<Void> response = controller.verifyOtp(REF, da, otpRequest("4821"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(pickupOtpService).verify(SHIPMENT_ID, "4821");
        verify(stateMachine).transition(eq(SHIPMENT_ID), eq(ShipmentState.PICKED_UP), any());
    }

    @Test
    void verifyOtp_shipmentNotInPickupAssigned_returns409() {
        when(shipmentRepository.findByShipmentRef(REF))
                .thenReturn(Optional.of(shipment(ShipmentState.BOOKED)));

        assertThatThrownBy(() -> controller.verifyOtp(REF, da, otpRequest("4821")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void verifyOtp_wrongOtp_returns422() {
        when(shipmentRepository.findByShipmentRef(REF))
                .thenReturn(Optional.of(shipment(ShipmentState.PICKUP_ASSIGNED)));
        doThrow(new PickupOtpService.OtpVerificationException("OTP is incorrect"))
                .when(pickupOtpService).verify(any(), any());

        assertThatThrownBy(() -> controller.verifyOtp(REF, da, otpRequest("9999")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void verifyOtp_shipmentNotFound_returns404() {
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.verifyOtp(REF, da, otpRequest("4821")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void verifyOtp_nonDaRole_returns403() {
        AuthUserDetails customer = principalWithRole("C2C_CUSTOMER", "00000000-0000-0000-0000-0000000c0001");

        assertThatThrownBy(() -> controller.verifyOtp(REF, customer, otpRequest("4821")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // -------------------------------------------------------------------------
    // resendOtp
    // -------------------------------------------------------------------------

    @Test
    void resendOtp_belowLimit_returns200WithNewOtp() {
        when(shipmentRepository.findByShipmentRef(REF))
                .thenReturn(Optional.of(shipment(ShipmentState.PICKUP_ASSIGNED)));
        when(pickupOtpService.resend(SHIPMENT_ID)).thenReturn("3902");

        ResponseEntity<Map<String, String>> response = controller.resendOtp(REF, da);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("otp", "3902");
    }

    @Test
    void resendOtp_limitExceeded_returns429() {
        when(shipmentRepository.findByShipmentRef(REF))
                .thenReturn(Optional.of(shipment(ShipmentState.PICKUP_ASSIGNED)));
        doThrow(new PickupOtpService.ResendLimitExceededException("Resend limit reached"))
                .when(pickupOtpService).resend(SHIPMENT_ID);

        assertThatThrownBy(() -> controller.resendOtp(REF, da))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void resendOtp_shipmentNotInPickupAssigned_returns409() {
        when(shipmentRepository.findByShipmentRef(REF))
                .thenReturn(Optional.of(shipment(ShipmentState.BOOKED)));

        assertThatThrownBy(() -> controller.resendOtp(REF, da))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void resendOtp_shipmentNotFound_returns404() {
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.resendOtp(REF, da))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Shipment shipment(ShipmentState state) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
        s.setState(state);
        return s;
    }

    private OtpVerifyRequest otpRequest(String otp) {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setOtp(otp);
        return req;
    }
}
