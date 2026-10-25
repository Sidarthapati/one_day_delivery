package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.MyShipmentDetailResponse;
import com.oneday.orders.service.CustomerOrderQueryService;
import com.oneday.orders.service.ParcelMeasurementService;
import com.oneday.orders.service.PickupOtpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class MyShipmentsControllerTest {

    @Mock CustomerOrderQueryService query;
    @Mock PickupOtpService pickupOtpService;
    @Mock ParcelMeasurementService parcelMeasurementService;
    @InjectMocks MyShipmentsController controller;

    private final AuthUserDetails cust = DeliveryOtpControllerTest.principal("B2C_CUSTOMER");

    @Test
    void myShipments_delegates() {
        when(query.myShipments(anyString(), anyInt())).thenReturn(List.of());
        assertThat(controller.myShipments(cust, 50)).isEmpty();
    }

    @Test
    void myShipmentDetail_present_and_404() {
        MyShipmentDetailResponse detail = mock(MyShipmentDetailResponse.class);
        when(query.myShipmentDetail(anyString(), eq("REF"))).thenReturn(Optional.of(detail));
        assertThat(controller.myShipmentDetail(cust, "REF")).isSameAs(detail);

        when(query.myShipmentDetail(anyString(), eq("X"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.myShipmentDetail(cust, "X"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void shipmentLabel_404_whenEmpty() {
        when(query.shipmentLabel(anyString(), eq("X"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.shipmentLabel(cust, "X"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void measurements_ownershipGate_thenHistory() {
        when(query.myShipmentDetail(anyString(), eq("REF")))
                .thenReturn(Optional.of(mock(MyShipmentDetailResponse.class)));
        when(parcelMeasurementService.history("REF", true)).thenReturn(List.of());
        assertThat(controller.myShipmentMeasurements(cust, "REF")).isEmpty();

        when(query.myShipmentDetail(anyString(), eq("X"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.myShipmentMeasurements(cust, "X"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void pickupOtp_present_and_404() {
        when(pickupOtpService.peekForOwner(anyString(), eq("REF"))).thenReturn(Optional.of("1234"));
        assertThat(controller.pickupOtp(cust, "REF").otp()).isEqualTo("1234");

        when(pickupOtpService.peekForOwner(anyString(), eq("X"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.pickupOtp(cust, "X"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void regeneratePickupOtp_present_404_and_conflict() {
        when(pickupOtpService.regenerateForOwner(anyString(), eq("REF"))).thenReturn(Optional.of("5678"));
        assertThat(controller.regeneratePickupOtp(cust, "REF").otp()).isEqualTo("5678");

        when(pickupOtpService.regenerateForOwner(anyString(), eq("X"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.regeneratePickupOtp(cust, "X"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        when(pickupOtpService.regenerateForOwner(anyString(), eq("C")))
                .thenThrow(new IllegalStateException("not awaiting pickup"));
        assertThatThrownBy(() -> controller.regeneratePickupOtp(cust, "C"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
