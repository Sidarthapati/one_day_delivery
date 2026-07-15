package com.oneday.airline.service.impl;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.events.FlightEventProducer;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.service.AwbBookingService;
import com.oneday.common.kafka.enums.FlightReassignReason;
import com.oneday.common.kafka.events.flight.FlightReassignedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Moves one already-booked AWB onto a replacement flight (§7): finds the cheapest flight that still
 * meets the promise (reusing the same brain that assigns a flight in the first place), books it as a
 * fresh AWB for the same bag, and marks the old one superseded rather than deleting it — history is
 * never lost, a customer's tracking always resolves to the current flight. M7 executes the move once
 * it consumes the resulting {@link FlightReassignedEvent}.
 */
@Service
public class FlightReassignmentService {

    private final AwbRepository awbRepository;
    private final AwbBookingService awbBookingService;
    private final FlightSelectionService flightSelectionService;
    private final FlightEventProducer flightEventProducer;
    private final Clock clock;

    FlightReassignmentService(AwbRepository awbRepository, AwbBookingService awbBookingService,
                               FlightSelectionService flightSelectionService, FlightEventProducer flightEventProducer,
                               Clock clock) {
        this.awbRepository = awbRepository;
        this.awbBookingService = awbBookingService;
        this.flightSelectionService = flightSelectionService;
        this.flightEventProducer = flightEventProducer;
        this.clock = clock;
    }

    @Transactional
    public Awb reassign(Awb old, FlightReassignReason reason) {
        FlightSelectionService.Selection replacement =
                flightSelectionService.select(old.getOriginHub(), old.getDestHub(), clock.instant());

        // Free the bag_id slot (uq_awb_bag_booked, V9_10) before booking the replacement.
        old.setStatus(AwbStatus.SUPERSEDED);
        awbRepository.save(old);

        Awb replacementAwb = awbBookingService.book(new AwbBookingService.BookBagCommand(
                old.getBagId(), replacement.flightNo(), replacement.flightDate(),
                old.getParcelCount(), old.getTotalWeightGrams()));

        old.setSupersededBy(replacementAwb.getId());
        awbRepository.save(old);

        flightEventProducer.emitReassigned(new FlightReassignedEvent(
                replacement.flightNo(), replacement.flightDate(), old.getDestHub(), replacement.cutoff(),
                old.getFlightNo(), null, reason));   // null parcelIds = move the whole bag

        return replacementAwb;
    }
}
