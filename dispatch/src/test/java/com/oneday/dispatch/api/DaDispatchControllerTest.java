package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.dto.request.GpsPingRequest;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DaTaskView;
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
    private DaDispatchController controller;

    private final UUID da = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        daStatusService = mock(DaStatusService.class);
        daTaskService = mock(DaTaskService.class);
        controller = new DaDispatchController(daStatusService, daTaskService);
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
