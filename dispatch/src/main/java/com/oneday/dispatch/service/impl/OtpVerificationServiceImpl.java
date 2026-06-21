package com.oneday.dispatch.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.metrics.DispatchMetrics;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.OtpVerificationService;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Drives M4's pickup OTP in-process (design §6.2). Verify → state-machine PICKED_UP → emit
 * PICKUP_COMPLETED (gated). Errors map to HTTP: bad/expired OTP → 422, illegal shipment state → 409,
 * resend limit → 429, unknown/foreign task → 404.
 */
@Service
class OtpVerificationServiceImpl implements OtpVerificationService {

    private static final Logger log = LoggerFactory.getLogger(OtpVerificationServiceImpl.class);

    private final DispatchQueueRepository queueRepository;
    private final PickupOtpService pickupOtpService;
    private final ShipmentStateMachine stateMachine;
    private final DaEventProducer daEventProducer;
    private final DispatchMetrics metrics;

    OtpVerificationServiceImpl(DispatchQueueRepository queueRepository,
                               PickupOtpService pickupOtpService,
                               ShipmentStateMachine stateMachine,
                               DaEventProducer daEventProducer,
                               DispatchMetrics metrics) {
        this.queueRepository = queueRepository;
        this.pickupOtpService = pickupOtpService;
        this.stateMachine = stateMachine;
        this.daEventProducer = daEventProducer;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public void verifyOtp(UUID daId, UUID taskId, String otp) {
        DispatchQueue task = pickupTask(daId, taskId);
        UUID shipmentId = task.getShipmentId();
        try {
            pickupOtpService.verify(shipmentId, otp);
        } catch (PickupOtpService.OtpVerificationException e) {
            metrics.otpVerify("INVALID");
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        try {
            stateMachine.transition(shipmentId, ShipmentState.PICKED_UP,
                    TransitionContext.fromApi(daId.toString(), shipmentId.toString()));
        } catch (IllegalStateTransitionException e) {
            metrics.otpVerify("ERROR");
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        daEventProducer.emitPickupCompleted(daId, task.getCityId(), shipmentId);
        metrics.otpVerify("SUCCESS");
        log.debug("Pickup OTP verified for shipment {} (task {})", shipmentId, taskId);
    }

    @Override
    @Transactional
    public void resendOtp(UUID daId, UUID taskId) {
        DispatchQueue task = pickupTask(daId, taskId);
        try {
            pickupOtpService.resend(task.getShipmentId());
        } catch (PickupOtpService.ResendLimitExceededException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        } catch (PickupOtpService.OtpVerificationException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        // New cleartext OTP is dispatched to the sender by M4/notification — not returned here.
    }

    private DispatchQueue pickupTask(UUID daId, UUID taskId) {
        DispatchQueue task = queueRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such task " + taskId));
        if (!task.getDaId().equals(daId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such task " + taskId + " for DA " + daId);
        }
        if (task.getTaskType() != TaskType.PICKUP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task " + taskId + " is not a pickup");
        }
        return task;
    }
}
