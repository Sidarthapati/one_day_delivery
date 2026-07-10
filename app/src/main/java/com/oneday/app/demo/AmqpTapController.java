package com.oneday.app.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Demo-only ({@code @Profile("!prod")}) read surface over {@link AmqpTap}. The Execution tab polls this
 * during a run and appends each entry to the live RabbitMQ feed as a real PUBLISH / CONSUME line.
 *
 * <p>{@code GET /api/demo/amqp-tap?after=<seq>} returns entries newer than {@code after} plus the
 * current head sequence. A client fast-forwards its cursor to {@code head} at run start so the feed
 * shows only this run's traffic.</p>
 */
@RestController
@RequestMapping("/api/demo/amqp-tap")
@Profile("!prod")
class AmqpTapController {

    private final AmqpTap tap;

    AmqpTapController(AmqpTap tap) {
        this.tap = tap;
    }

    public record TapResponse(long head, List<AmqpTap.Entry> entries) {}

    @GetMapping
    public TapResponse poll(@RequestParam(value = "after", defaultValue = "0") long after) {
        return new TapResponse(tap.head(), tap.since(after));
    }
}
