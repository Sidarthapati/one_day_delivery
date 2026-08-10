package com.oneday.airline.service;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.repository.AwbRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Records the <b>real</b> air waybill number the freight consolidator (Bhagwati) hands us for a flight,
 * replacing the local placeholder minted at booking. Bhagwati isn't tech-heavy — there's no booking API
 * — so the AWB arrives out-of-band (admin entry now, a WhatsApp form later) and is stamped here.
 *
 * <p>One AWB covers the whole plane, so every BOOKED AWB row on that (flightNo, date) — one per sealed
 * bag — is set to the same real number; that number then drives cargo-status lookups. Idempotent: a
 * re-submit just re-writes the same value.</p>
 */
@Service
public class AwbIntakeService {

    private static final Logger log = LoggerFactory.getLogger(AwbIntakeService.class);

    private final AwbRepository awbRepository;

    public AwbIntakeService(AwbRepository awbRepository) {
        this.awbRepository = awbRepository;
    }

    /** Stamp {@code awbNo} on every booked AWB for the flight. Returns how many rows were updated (0 → none booked). */
    @Transactional
    public int assignRealAwb(String flightNo, LocalDate flightDate, String awbNo) {
        List<Awb> booked = awbRepository.findByFlightNoAndFlightDate(flightNo, flightDate).stream()
                .filter(a -> a.getStatus() == AwbStatus.BOOKED)
                .toList();
        booked.forEach(a -> a.setAwbNo(awbNo));
        awbRepository.saveAll(booked);
        log.info("Stamped real AWB {} on {} booked bag(s) for flight {} ({})",
                awbNo, booked.size(), flightNo, flightDate);
        return booked.size();
    }
}
