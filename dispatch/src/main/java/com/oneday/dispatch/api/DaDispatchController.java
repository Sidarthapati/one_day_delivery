package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.dto.request.DropCompletedRequest;
import com.oneday.dispatch.dto.request.GpsPingRequest;
import com.oneday.dispatch.dto.request.OtpVerifyRequest;
import com.oneday.dispatch.dto.request.TaskFailedRequest;
import com.oneday.dispatch.dto.request.VanHandoffRequest;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DaTaskView;
import com.oneday.dispatch.service.OtpVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The DA mobile app's dispatch endpoints. Every call is authorized to the DA themselves (or ADMIN)
 * via {@link Authz#requireDaSelf}; task-lifecycle work is delegated to {@link DaTaskService}, GPS to
 * {@link DaStatusService}. Errors surface as 401/403 (auth), 404 (unknown task), 409 (illegal
 * transition), 422 (missing scans).
 */
@RestController
@RequestMapping("/dispatch/da/{daId}")
public class DaDispatchController {

    private final DaStatusService daStatusService;
    private final DaTaskService daTaskService;
    private final OtpVerificationService otpVerificationService;

    public DaDispatchController(DaStatusService daStatusService, DaTaskService daTaskService,
                               OtpVerificationService otpVerificationService) {
        this.daStatusService = daStatusService;
        this.daTaskService = daTaskService;
        this.otpVerificationService = otpVerificationService;
    }

    @PostMapping("/gps")
    public ResponseEntity<Void> gps(@PathVariable UUID daId,
                                    @AuthenticationPrincipal AuthUserDetails principal,
                                    @Valid @RequestBody GpsPingRequest request) {
        Authz.requireDaSelf(principal, daId);
        Instant ts = request.timestamp() != null ? request.timestamp() : Instant.now();
        daStatusService.updateGps(daId, request.lat(), request.lon(), ts);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{taskId}/en-route")
    public DaTaskView enRoute(@PathVariable UUID daId, @PathVariable UUID taskId,
                              @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return daTaskService.markEnRoute(daId, taskId);
    }

    @PostMapping("/tasks/{taskId}/van-handoff")
    public DaTaskView vanHandoff(@PathVariable UUID daId, @PathVariable UUID taskId,
                                 @AuthenticationPrincipal AuthUserDetails principal,
                                 @RequestBody VanHandoffRequest request) {
        Authz.requireDaSelf(principal, daId);
        return daTaskService.recordVanHandoff(daId, taskId, request.parcelScans(), request.vanId());
    }

    @PostMapping("/tasks/{taskId}/failed")
    public DaTaskView failed(@PathVariable UUID daId, @PathVariable UUID taskId,
                             @AuthenticationPrincipal AuthUserDetails principal,
                             @RequestBody(required = false) TaskFailedRequest request) {
        Authz.requireDaSelf(principal, daId);
        String reason = request != null ? request.reason() : null;
        return daTaskService.markFailed(daId, taskId, reason);
    }

    @PostMapping("/tasks/{taskId}/drop-collected")
    public DaTaskView dropCollected(@PathVariable UUID daId, @PathVariable UUID taskId,
                                    @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return daTaskService.markDropCollected(daId, taskId);
    }

    @PostMapping("/tasks/{taskId}/drop-completed")
    public DaTaskView dropCompleted(@PathVariable UUID daId, @PathVariable UUID taskId,
                                    @AuthenticationPrincipal AuthUserDetails principal,
                                    @RequestBody(required = false) DropCompletedRequest request) {
        Authz.requireDaSelf(principal, daId);
        boolean cod = request != null && request.codCollected();
        return daTaskService.markDropCompleted(daId, taskId, cod);
    }

    @PostMapping("/tasks/{taskId}/verify-otp")
    public ResponseEntity<Void> verifyOtp(@PathVariable UUID daId, @PathVariable UUID taskId,
                                          @AuthenticationPrincipal AuthUserDetails principal,
                                          @Valid @RequestBody OtpVerifyRequest request) {
        Authz.requireDaSelf(principal, daId);
        otpVerificationService.verifyOtp(daId, taskId, request.otp());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{taskId}/resend-otp")
    public ResponseEntity<Void> resendOtp(@PathVariable UUID daId, @PathVariable UUID taskId,
                                          @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        otpVerificationService.resendOtp(daId, taskId);
        return ResponseEntity.noContent().build();
    }
}
