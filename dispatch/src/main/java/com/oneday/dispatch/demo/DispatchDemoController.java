package com.oneday.dispatch.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oneday.dispatch.domain.TaskType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Demo-only M5 dispatch surface for the UI's "Dispatch" tab (see {@link DispatchDemoService}). Sits
 * under {@code /api/demo/dispatch} alongside the grid + routing demo controllers. {@code @Profile("!prod")}.
 */
@RestController
@RequestMapping("/api/demo/dispatch")
@Profile("!prod")
public class DispatchDemoController {

    private final DispatchDemoService service;

    DispatchDemoController(DispatchDemoService service) {
        this.service = service;
    }

    @PostMapping("/load-shift")
    public DispatchDemoService.DispatchState loadShift(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.loadShift(cityId, date);
    }

    @PostMapping("/assign")
    public DispatchDemoService.AssignResult assign(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "20") int count) {
        return service.assign(cityId, date, count);
    }

    @PostMapping("/assign-deliveries")
    public DispatchDemoService.AssignResult assignDeliveries(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "20") int count) {
        return service.assignDeliveries(cityId, date, count);
    }

    @PostMapping("/work-next")
    public DispatchDemoService.DispatchState workNext(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.workNext(cityId, date);
    }

    @PostMapping("/cancel-task")
    public DispatchDemoService.DispatchState cancelTask(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam UUID shipmentId, @RequestParam TaskType taskType) {
        return service.cancelTask(cityId, date, shipmentId, taskType);
    }

    @PostMapping("/mark-absent")
    public DispatchDemoService.DispatchState markAbsent(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam UUID daId) {
        return service.markAbsent(cityId, date, daId);
    }

    @PostMapping("/end-shift")
    public DispatchDemoService.DispatchState endShift(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.endShift(cityId, date);
    }

    @PostMapping("/retry-deferred")
    public DispatchDemoService.DispatchState retryDeferred(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.retryDeferred(cityId, date);
    }

    @GetMapping("/state")
    public DispatchDemoService.DispatchState state(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.state(cityId, date);
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@RequestParam UUID cityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.reset(cityId, date);
        return ResponseEntity.noContent().build();
    }
}
