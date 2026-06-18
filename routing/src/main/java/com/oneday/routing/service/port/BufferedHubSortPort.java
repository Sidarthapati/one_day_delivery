package com.oneday.routing.service.port;

import com.oneday.routing.domain.InboundKind;
import com.oneday.routing.repository.InboundParcelRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Reads DELIVER rows the HubFeedConsumer accumulated from M7's sorted-for-delivery events.
@Component
public class BufferedHubSortPort implements HubSortPort {

    private final InboundParcelRepository repository;

    public BufferedHubSortPort(InboundParcelRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ReadyForDeliveryParcel> readyForDelivery(UUID cityId, LocalDate date) {
        return repository.findByCityIdAndValidDateAndKind(cityId, date, InboundKind.DELIVER).stream()
                .map(p -> new ReadyForDeliveryParcel(p.getParcelId(), p.getDestinationHexId(), p.getReadyAt(), p.getSlaDeadline()))
                .toList();
    }
}
