package com.oneday.hub.api;

import com.oneday.hub.dto.BatchReceiveRequest;
import com.oneday.hub.dto.ReceiveRequest;
import com.oneday.hub.dto.ReceiveResponse;
import com.oneday.hub.service.HubReceivingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Operator console: the dock (§14.2). City-scoped auth (M1) lands with the auth wiring. */
@RestController
@RequestMapping("/hub/{hubId}")
public class HubReceivingController {

    private final HubReceivingService receivingService;

    HubReceivingController(HubReceivingService receivingService) {
        this.receivingService = receivingService;
    }

    @PostMapping("/receive")
    public ResponseEntity<ReceiveResponse> receive(@PathVariable UUID hubId,
                                                   @RequestBody @Valid ReceiveRequest request) {
        var result = receivingService.receive(hubId, request.shipmentRef());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReceiveResponse.from(result));
    }

    @PostMapping("/receive/batch")
    public ResponseEntity<List<ReceiveResponse>> receiveBatch(@PathVariable UUID hubId,
                                                              @RequestBody @Valid BatchReceiveRequest request) {
        List<ReceiveResponse> out = request.shipmentRefs().stream()
                .map(ref -> ReceiveResponse.from(receivingService.receive(hubId, ref)))
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }
}
