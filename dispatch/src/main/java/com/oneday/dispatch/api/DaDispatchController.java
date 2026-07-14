package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.MeetingMode;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.dispatch.dto.request.DropCompletedRequest;
import com.oneday.dispatch.dto.request.GpsPingRequest;
import com.oneday.dispatch.dto.request.HubHandoffRequest;
import com.oneday.dispatch.dto.request.OtpVerifyRequest;
import com.oneday.dispatch.dto.request.TaskFailedRequest;
import com.oneday.dispatch.dto.request.VanHandoffRequest;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DaTaskView;
import com.oneday.dispatch.service.OtpVerificationService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    private final CityMeetingModePort meetingModePort;

    public DaDispatchController(DaStatusService daStatusService, DaTaskService daTaskService,
                               OtpVerificationService otpVerificationService,
                               CityMeetingModePort meetingModePort) {
        this.daStatusService = daStatusService;
        this.daTaskService = daTaskService;
        this.otpVerificationService = otpVerificationService;
        this.meetingModePort = meetingModePort;
    }

    /** The DA's task queue for the day (the app's home list). Each item carries taskLat/taskLon for Open-in-Maps. */
    @GetMapping("/tasks")
    public List<DaTaskView> tasks(@PathVariable UUID daId,
                                  @AuthenticationPrincipal AuthUserDetails principal,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Authz.requireDaSelf(principal, daId);
        return daTaskService.listTasks(daId, date);
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

    /** Manual "Mark arrived" at the van meeting vertex (replaces the removed geofence). */
    @PostMapping("/arrived")
    public ResponseEntity<Void> arrived(@PathVariable UUID daId,
                                        @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        daStatusService.markArrivedAtCron(daId);
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

    @PostMapping("/tasks/{taskId}/hub-handoff")
    public DaTaskView hubHandoff(@PathVariable UUID daId, @PathVariable UUID taskId,
                                 @AuthenticationPrincipal AuthUserDetails principal,
                                 @RequestBody HubHandoffRequest request) {
        Authz.requireDaSelf(principal, daId);
        requireHubReturnCity(daId);
        return daTaskService.recordHubHandoff(daId, taskId, request.parcelScans());
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

    @PostMapping("/tasks/{taskId}/hub-collect")
    public DaTaskView hubCollect(@PathVariable UUID daId, @PathVariable UUID taskId,
                                 @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        requireHubReturnCity(daId);
        return daTaskService.recordHubCollect(daId, taskId);
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

    /**
     * Cheap edge guard: the hub-handoff / hub-collect endpoints are only valid in a HUB_RETURN city.
     * The DA's city (from their live status) resolves the mode; a VAN_MEETING city → 409 so a misrouted
     * client can't emit hub events where a van rendezvous is expected. The service itself stays mode-agnostic.
     */
    private void requireHubReturnCity(UUID daId) {
        UUID cityId = daStatusService.getLiveStatus(daId).getCityId();
        if (meetingModePort.modeFor(cityId) != MeetingMode.HUB_RETURN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hub handoff/collect is only valid in a HUB_RETURN city");
        }
    }
}
