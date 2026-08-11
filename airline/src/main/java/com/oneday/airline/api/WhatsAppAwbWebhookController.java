package com.oneday.airline.api;

import com.oneday.airline.service.AwbIntakeService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;

/**
 * <b>STUB — not yet wired to Meta.</b> The intended path for Bhagwati (non-tech) to return an AWB: they
 * answer a WhatsApp Business "Flow" (a small form: flight number, date, AWB) and a BSP posts the parsed
 * answer here, which stamps it via {@link AwbIntakeService} — the exact same effect as the admin entry
 * on {@code AirlineController}.
 *
 * <p>Deferred before this is production-facing: Meta webhook signature verification (X-Hub-Signature-256),
 * the Flow schema → field binding, and the verify/challenge handshake. Until then this door accepts a
 * plain internal JSON body and should stay behind app-level auth — treat it as an internal test seam.</p>
 */
@RestController
@RequestMapping("/airline/webhooks/whatsapp")
class WhatsAppAwbWebhookController {

    private final AwbIntakeService intake;

    WhatsAppAwbWebhookController(AwbIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping("/awb")
    Map<String, Object> onAwbSubmitted(@RequestBody WhatsAppAwbForm form) {
        LocalDate date = LocalDate.parse(form.flightDate());
        int updated = intake.assignRealAwb(form.flightNo(), date, form.awbNo());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No booked AWB for flight " + form.flightNo() + " (" + form.flightDate() + ")");
        }
        return Map.of("status", "ok", "bagsUpdated", updated);
    }

    /** The flattened answers a WhatsApp Flow submission would carry. */
    record WhatsAppAwbForm(@NotBlank String flightNo,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) String flightDate,
                           @NotBlank String awbNo) {}
}
