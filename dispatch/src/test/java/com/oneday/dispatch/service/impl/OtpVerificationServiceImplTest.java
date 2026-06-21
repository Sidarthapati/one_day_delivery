package com.oneday.dispatch.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.OtpVerificationService;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Real-Postgres test of the OTP service: it looks up the task (real repo) and drives the mocked M4
 * in-process services (PickupOtpService, ShipmentStateMachine), mapping their failures to HTTP codes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OtpVerificationServiceImplTest {

    @Autowired DispatchQueueRepository queueRepo;

    private final UUID da = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();
    private final LocalDate today = LocalDate.now();

    private PickupOtpService pickupOtpService;
    private ShipmentStateMachine stateMachine;
    private DaEventProducer events;
    private OtpVerificationService service;

    @BeforeEach
    void setUp() {
        pickupOtpService = mock(PickupOtpService.class);
        stateMachine = mock(ShipmentStateMachine.class);
        events = mock(DaEventProducer.class);
        service = new OtpVerificationServiceImpl(queueRepo, pickupOtpService, stateMachine, events);
    }

    @Test
    void verifyTransitionsAndEmitsOnCorrectOtp() {
        DispatchQueue task = persistPickup();
        service.verifyOtp(da, task.getId(), "4821");

        verify(pickupOtpService).verify(task.getShipmentId(), "4821");
        verify(stateMachine).transition(eq(task.getShipmentId()), eq(ShipmentState.PICKED_UP), any());
        verify(events).emitPickupCompleted(eq(da), eq(city), eq(task.getShipmentId()));
    }

    @Test
    void wrongOtpIs422AndDoesNotTransition() {
        DispatchQueue task = persistPickup();
        doThrow(new PickupOtpService.OtpVerificationException("OTP_INVALID"))
                .when(pickupOtpService).verify(eq(task.getShipmentId()), any());

        assertThatThrownBy(() -> service.verifyOtp(da, task.getId(), "0000"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
        verify(stateMachine, never()).transition(any(), any(), any());
        verify(events, never()).emitPickupCompleted(any(), any(), any());
    }

    @Test
    void illegalShipmentStateIs409() {
        DispatchQueue task = persistPickup();
        doThrow(new IllegalStateTransitionException(ShipmentState.PICKED_UP, ShipmentState.PICKED_UP))
                .when(stateMachine).transition(eq(task.getShipmentId()), eq(ShipmentState.PICKED_UP), any());

        assertThatThrownBy(() -> service.verifyOtp(da, task.getId(), "4821"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void unknownTaskIs404() {
        assertThatThrownBy(() -> service.verifyOtp(da, UUID.randomUUID(), "4821"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void nonPickupTaskIs409() {
        DispatchQueue task = persist(TaskType.DELIVERY);
        assertThatThrownBy(() -> service.verifyOtp(da, task.getId(), "4821"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void resendDelegates() {
        DispatchQueue task = persistPickup();
        service.resendOtp(da, task.getId());
        verify(pickupOtpService).resend(task.getShipmentId());
    }

    @Test
    void resendLimitIs429() {
        DispatchQueue task = persistPickup();
        doThrow(new PickupOtpService.ResendLimitExceededException("limit"))
                .when(pickupOtpService).resend(task.getShipmentId());

        assertThatThrownBy(() -> service.resendOtp(da, task.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
    }

    private DispatchQueue persistPickup() {
        return persist(TaskType.PICKUP);
    }

    private DispatchQueue persist(TaskType type) {
        DispatchQueue d = new DispatchQueue();
        d.setDaId(da);
        d.setCityId(city);
        d.setShipmentId(UUID.randomUUID());
        d.setTaskType(type);
        d.setTaskLat(12.97);
        d.setTaskLon(77.61);
        d.setTileId(UUID.randomUUID());
        d.setQueuePosition(0);
        d.setStatus(TaskStatus.IN_PROGRESS);
        d.setCronSafe(true);
        d.setOperatingDate(today);
        return queueRepo.saveAndFlush(d);
    }
}
