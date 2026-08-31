package com.oneday.exceptions.api;

import com.oneday.exceptions.service.WhatsAppInboundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound WhatsApp webhook (Meta Cloud API) — the door a customer's WhatsApp reply arrives through.
 * Thin HTTP layer only; the verify/signature/parse logic lives in {@link WhatsAppInboundService}.
 *
 * <p>Public (no JWT) because Meta calls it unauthenticated — authenticity comes from the signature, not
 * a bearer token. Because the path is public, an <b>unverifiable</b> POST is rejected (403): until an
 * app secret is configured (no BSP account yet) every POST is 403, so nobody can push forged inbound
 * messages. See {@link WhatsAppInboundService} for the stub/deferred details (routing → issue #182).
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
class WhatsAppInboundController {

    private final WhatsAppInboundService service;

    WhatsAppInboundController(WhatsAppInboundService service) {
        this.service = service;
    }

    /** Meta verify handshake: echo the challenge iff the mode + token match. */
    @GetMapping
    ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                  @RequestParam(name = "hub.verify_token", required = false) String token,
                                  @RequestParam(name = "hub.challenge", required = false) String challenge) {
        // Meta's hub.challenge is always an integer; echo it ONLY if it's purely numeric (so a crafted
        // value can never be reflected as markup) and as text/plain, and only on a valid handshake.
        if (service.isValidHandshake(mode, token) && challenge != null && challenge.matches("\\d{1,32}")) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /** Receive inbound messages: 200 when authenticated + processed, 403 when unverifiable. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                 @RequestBody byte[] rawBody) {
        return service.receive(signature, rawBody)
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
