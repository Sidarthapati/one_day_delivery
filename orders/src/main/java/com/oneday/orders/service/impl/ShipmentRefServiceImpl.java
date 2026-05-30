package com.oneday.orders.service.impl;

import com.oneday.orders.domain.ShipmentRefCounter;
import com.oneday.orders.domain.ShipmentRefCounterId;
import com.oneday.orders.repository.ShipmentRefCounterRepository;
import com.oneday.orders.service.ShipmentRefService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Generates shipment reference numbers of the form {@code 1DD-{CITY}-{YYYYMMDD}-{NNNNN}}.
 *
 * <p>Uses {@code SELECT FOR UPDATE} via {@link ShipmentRefCounterRepository#findByIdWithLock}
 * to serialise concurrent increments within a transaction. The caller's transaction
 * must already be active — this method runs with {@code MANDATORY} propagation so that
 * the counter increment is rolled back together with the surrounding booking if anything
 * fails after ref generation.</p>
 *
 * <p><strong>High-volume upgrade path:</strong> At > ~500 bookings/min per city the
 * row-level lock on {@code (city_code, date_key)} becomes a serialisation bottleneck.
 * Migrate to a Redis {@code INCR} + Lua script that atomically increments and returns
 * the counter, syncing to the DB asynchronously. See M4-ORDERS-DESIGN.md §15.5.</p>
 */
@Service
class ShipmentRefServiceImpl implements ShipmentRefService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String PREFIX = "1DD";

    private final ShipmentRefCounterRepository counterRepository;

    ShipmentRefServiceImpl(ShipmentRefCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Must be called inside an active transaction ({@code MANDATORY} propagation).
     * The counter increment is part of the caller's transaction and will be rolled
     * back if the surrounding booking fails.</p>
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String generateRef(String originCityCode) {
        String city = originCityCode.toUpperCase();
        LocalDate today = LocalDate.now(IST);

        ShipmentRefCounterId id = new ShipmentRefCounterId(city, today);

        // Ensure the row exists before locking — INSERT does nothing on conflict,
        // so only the first concurrent caller for this (city, date) pair inserts.
        // Without this, SELECT FOR UPDATE on a missing row locks nothing, letting
        // concurrent first-of-day callers race to INSERT and violate the PK.
        counterRepository.insertIfAbsent(city, today);

        // Lock the counter row so no other transaction can interleave.
        ShipmentRefCounter counter = counterRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Counter must exist after insertIfAbsent for id=" + id));

        int next = counter.getNextVal() + 1;
        counter.setNextVal(next);
        counterRepository.save(counter);

        // Format: 1DD-BLR-20260530-00042
        String dateKey = today.format(DateTimeFormatter.BASIC_ISO_DATE); // YYYYMMDD
        return String.format("%s-%s-%s-%05d", PREFIX, city, dateKey, next);
    }
}
