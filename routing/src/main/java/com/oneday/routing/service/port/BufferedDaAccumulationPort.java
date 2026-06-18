package com.oneday.routing.service.port;

import com.oneday.routing.domain.InboundKind;
import com.oneday.routing.repository.InboundParcelRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Reads COLLECT rows the DaFeedConsumer accumulated from M5's DA-pickup events.
@Component
public class BufferedDaAccumulationPort implements DaAccumulationPort {

    private final InboundParcelRepository repository;

    public BufferedDaAccumulationPort(InboundParcelRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AccumulatedParcel> collectedAwaitingPickup(UUID daId, LocalDate date) {
        return repository.findByDaIdAndValidDateAndKind(daId, date, InboundKind.COLLECT).stream()
                .map(p -> new AccumulatedParcel(p.getParcelId(), p.getDaId(), p.getPickedUpAt()))
                .toList();
    }
}
