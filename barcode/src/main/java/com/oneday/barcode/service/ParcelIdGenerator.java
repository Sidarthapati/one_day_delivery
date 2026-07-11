package com.oneday.barcode.service;

import com.oneday.barcode.domain.ParcelIdCounter;
import com.oneday.barcode.domain.ParcelIdCounterId;
import com.oneday.barcode.repository.ParcelIdCounterRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Mints the parcel barcode {@code 1DD-{destHubIATA}-{yyMMdd}-{seq6}} (D-002). {@code seq6} comes from
 * a per-hub, per-day counter under a row lock (mirrors {@code orders.ShipmentRefService}). Must run
 * inside the caller's transaction — the lock is held until commit.
 */
@Component
class ParcelIdGenerator {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    // ponytail: hardcoded IST; all 5 launch cities are in one zone. Make configurable if we go global.
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ParcelIdCounterRepository counters;

    ParcelIdGenerator(ParcelIdCounterRepository counters) {
        this.counters = counters;
    }

    /** Next barcode for a dest hub, for today (IST). Bumps the counter; call once per label. */
    String next(String hubIata) {
        LocalDate day = LocalDate.now(IST);
        counters.insertIfAbsent(hubIata, day);                       // materialise the row (ON CONFLICT DO NOTHING)
        ParcelIdCounter counter = counters.findByIdWithLock(new ParcelIdCounterId(hubIata, day))
                .orElseThrow(() -> new IllegalStateException(
                        "parcel_id_counter row missing after insertIfAbsent: " + hubIata + " " + day));
        int seq = counter.getNextSeq();
        counter.setNextSeq(seq + 1);                                 // flushed on commit under the lock
        return "1DD-%s-%s-%06d".formatted(hubIata, day.format(YYMMDD), seq);
    }
}
