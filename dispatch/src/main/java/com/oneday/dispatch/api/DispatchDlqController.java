package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.service.DlqReplayService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ADMIN-only dead-letter re-drive. After a transient failure is fixed, an operator replays a stream's
 * parked messages back onto it for reprocessing.
 */
@RestController
public class DispatchDlqController {

    private final DlqReplayService dlqReplayService;

    public DispatchDlqController(DlqReplayService dlqReplayService) {
        this.dlqReplayService = dlqReplayService;
    }

    @PostMapping("/internal/v1/dispatch/dlq/replay")
    public Map<String, Object> replay(@RequestParam String stream,
                                      @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.ADMIN);
        int replayed = dlqReplayService.replay(stream);
        return Map.of("stream", stream, "replayed", replayed);
    }
}
