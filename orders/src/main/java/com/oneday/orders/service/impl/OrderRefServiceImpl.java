package com.oneday.orders.service.impl;

import com.oneday.orders.domain.OrderRefCounter;
import com.oneday.orders.domain.OrderRefCounterId;
import com.oneday.orders.repository.OrderRefCounterRepository;
import com.oneday.orders.service.OrderRefService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Generates order refs of the form {@code 1DD-ORD-{CITY}-{YYYYMMDD}-{NNNNN}}.
 * Same serialisation strategy as {@link ShipmentRefServiceImpl} — a row-level lock on
 * {@code (city_code, date_key)}. Must run inside the caller's transaction (MANDATORY) so the
 * counter increment rolls back with a failed booking.
 */
@Service
class OrderRefServiceImpl implements OrderRefService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String PREFIX = "1DD-ORD";

    private final OrderRefCounterRepository counterRepository;

    OrderRefServiceImpl(OrderRefCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String generateRef(String originCityCode) {
        String city = originCityCode.toUpperCase();
        LocalDate today = LocalDate.now(IST);

        OrderRefCounterId id = new OrderRefCounterId(city, today);

        // Ensure the row exists before locking (SELECT FOR UPDATE cannot lock a missing row).
        counterRepository.insertIfAbsent(city, today);

        OrderRefCounter counter = counterRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Order counter must exist after insertIfAbsent for id=" + id));

        int next = counter.getNextVal() + 1;
        counter.setNextVal(next);
        counterRepository.save(counter);

        String dateKey = today.format(DateTimeFormatter.BASIC_ISO_DATE); // YYYYMMDD
        return String.format("%s-%s-%s-%05d", PREFIX, city, dateKey, next);
    }
}
