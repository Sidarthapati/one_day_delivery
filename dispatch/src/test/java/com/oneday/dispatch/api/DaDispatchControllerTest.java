package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.MeetingMode;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.dispatch.dto.request.GpsPingRequest;
import com.oneday.dispatch.dto.request.HubHandoffRequest;
import com.oneday.dispatch.dto.request.OtpVerifyRequest;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DaTaskView;
import com.oneday.dispatch.service.OtpVerificationService;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for the DA controller's authorization and delegation (no MockMvc needed). */
class DaDispatchControllerTest {

    private DaStatusService daStatusService;
    private DaTaskService daTaskService;
    private OtpVerificationService otpVerificationService;
    private CityMeetingModePort meetingModePort;
    private DaDispatchController controller;

    private final UUID da = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        daStatusService = mock(DaStatusService.class);
        daTaskService = mock(DaTaskService.class);
        otpVerificationService = mock(OtpVerificationService.class);
        meetingModePort = mock(CityMeetingModePort.class);
        controller = new DaDispatchController(daStatusService, daTaskService, otpVerificationService, meetingModePort);
        when(daTaskService.markEnRoute(any(), any())).thenReturn(sampleView());
    }

    @Test
    void gpsDelegatesAndReturns204() {
        var resp = controller.gps(da, principal(da, "DELIVERY_ASSOCIATE"), new GpsPingRequest(12.9, 77.6, null));
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(daStatusService).updateGps(eq(da), eq(12.9), eq(77.6), any(Instant.class));
    }

    @Test
    void enRouteDelegatesForOwningDa() {
        controller.enRoute(da, taskId, principal(da, "DELIVERY_ASSOCIATE"));
        verify(daTaskService).markEnRoute(da, taskId);
    }

    @Test
    void forbiddenWhenActingForAnotherDa() {
        AuthUserDetails other = principal(UUID.randomUUID(), "DELIVERY_ASSOCIATE");
        assertThatThrownBy(() -> controller.enRoute(da, taskId, other))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verifyNoInteractions(daTaskService);
    }

    @Test
    void unauthorizedWhenNoPrincipal() {
        assertThatThrownBy(() -> controller.enRoute(da, taskId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void adminMayActForAnyDa() {
        controller.enRoute(da, taskId, principal(UUID.randomUUID(), "ADMIN"));
        verify(daTaskService).markEnRoute(da, taskId);
    }

    @Test
    void verifyOtpDelegatesAndReturns204() {
        var resp = controller.verifyOtp(da, taskId, principal(da, "DELIVERY_ASSOCIATE"), new OtpVerifyRequest("4821"));
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(otpVerificationService).verifyOtp(da, taskId, "4821");
    }

    @Test
    void resendOtpDelegatesAndReturns204() {
        var resp = controller.resendOtp(da, taskId, principal(da, "DELIVERY_ASSOCIATE"));
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(otpVerificationService).resendOtp(da, taskId);
    }

    @Test
    void hubHandoffDelegatesWhenCityIsHubReturn() {
        stubCityMode(MeetingMode.HUB_RETURN);
        controller.hubHandoff(da, taskId, principal(da, "DELIVERY_ASSOCIATE"),
                new HubHandoffRequest(java.util.List.of("P-1"), null));
        verify(daTaskService).recordHubHandoff(da, taskId, java.util.List.of("P-1"));
    }

    @Test
    void hubHandoffRejectedWithConflictInVanCity() {
        stubCityMode(MeetingMode.VAN_MEETING);
        assertThatThrownBy(() -> controller.hubHandoff(da, taskId, principal(da, "DELIVERY_ASSOCIATE"),
                new HubHandoffRequest(java.util.List.of("P-1"), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(daTaskService, org.mockito.Mockito.never()).recordHubHandoff(any(), any(), any());
    }

    @Test
    void hubCollectDelegatesWhenCityIsHubReturn() {
        stubCityMode(MeetingMode.HUB_RETURN);
        controller.hubCollect(da, taskId, principal(da, "DELIVERY_ASSOCIATE"));
        verify(daTaskService).recordHubCollect(da, taskId);
    }

    /** Wire the DA's live status → city → meeting mode (flat, to avoid nested stubbing). */
    private void stubCityMode(MeetingMode mode) {
        DaLiveStatus status = mock(DaLiveStatus.class);
        when(status.getCityId()).thenReturn(city);
        when(daStatusService.getLiveStatus(da)).thenReturn(status);
        when(meetingModePort.modeFor(city)).thenReturn(mode);
    }

    private AuthUserDetails principal(UUID userId, String role) {
        AuthUserDetails p = mock(AuthUserDetails.class, RETURNS_DEEP_STUBS);
        when(p.getUserId()).thenReturn(userId);
        when(p.getUser().getRole().getName()).thenReturn(role);
        return p;
    }

    private DaTaskView sampleView() {
        return new DaTaskView(taskId, UUID.randomUUID(), TaskType.PICKUP, TaskStatus.IN_PROGRESS, 0, null);
    }
}
