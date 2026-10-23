package com.oneday.orders.api;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.OtpVerifyRequest;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DeliveryOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryOtpControllerTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock DeliveryOtpService deliveryOtpService;
    @Mock ShipmentStateMachine stateMachine;
    @InjectMocks DeliveryOtpController controller;

    private final AuthUserDetails da = principal("DELIVERY_ASSOCIATE");

    static AuthUserDetails principal(String role) {
        Role r = mock(Role.class);
        lenient().when(r.getName()).thenReturn(role);
        User u = mock(User.class);
        lenient().when(u.getRole()).thenReturn(r);
        AuthUserDetails p = mock(AuthUserDetails.class);
        lenient().when(p.getUser()).thenReturn(u);
        lenient().when(p.getUserId()).thenReturn(UUID.randomUUID());
        return p;
    }

    private static OtpVerifyRequest otpReq(String otp) {
        OtpVerifyRequest r = new OtpVerifyRequest();
        r.setOtp(otp);
        return r;
    }

    private Shipment shipment(ShipmentState state) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef("REF");
        s.setState(state);
        return s;
    }

    @Test
    void verify_notFound_404() {
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.verifyOtp("REF", da, otpReq("1234")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void verify_notOutForDelivery_409() {
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(shipment(ShipmentState.BOOKED)));
        assertThatThrownBy(() -> controller.verifyOtp("REF", da, otpReq("1234")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void verify_success_transitionsDropped_204() {
        Shipment s = shipment(ShipmentState.DROP_COLLECTED);
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        assertThat(controller.verifyOtp("REF", da, otpReq("1234")).getStatusCode().value()).isEqualTo(204);
        verify(stateMachine).transition(eq(s.getId()), eq(ShipmentState.DROPPED), any());
    }

    @Test
    void verify_badOtp_422() {
        Shipment s = shipment(ShipmentState.COLLECTED_FROM_HUB);
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        doThrow(new DeliveryOtpService.OtpVerificationException("bad"))
                .when(deliveryOtpService).verify(any(), any());
        assertThatThrownBy(() -> controller.verifyOtp("REF", da, otpReq("0000")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resend_success_200() {
        Shipment s = shipment(ShipmentState.DROP_COLLECTED);
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        when(deliveryOtpService.resend(s.getId())).thenReturn("9999");
        assertThat(controller.resendOtp("REF", da).getBody()).containsEntry("otp", "9999");
    }

    @Test
    void resend_notOutForDelivery_409() {
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(shipment(ShipmentState.AT_DEST_HUB)));
        assertThatThrownBy(() -> controller.resendOtp("REF", da)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resend_limitExceeded_429_and_otpError_422() {
        Shipment s = shipment(ShipmentState.DROP_COLLECTED);
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        doThrow(new DeliveryOtpService.ResendLimitExceededException("limit"))
                .when(deliveryOtpService).resend(s.getId());
        assertThatThrownBy(() -> controller.resendOtp("REF", da)).isInstanceOf(ResponseStatusException.class);

        doThrow(new DeliveryOtpService.OtpVerificationException("none"))
                .when(deliveryOtpService).resend(s.getId());
        assertThatThrownBy(() -> controller.resendOtp("REF", da)).isInstanceOf(ResponseStatusException.class);
    }
}
