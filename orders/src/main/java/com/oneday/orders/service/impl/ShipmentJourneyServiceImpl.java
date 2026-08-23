package com.oneday.orders.service.impl;

import com.oneday.orders.dto.JourneyStep;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.ShipmentJourneyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class ShipmentJourneyServiceImpl implements ShipmentJourneyService {

    private final ShipmentStateHistoryRepository historyRepo;

    ShipmentJourneyServiceImpl(ShipmentStateHistoryRepository historyRepo) {
        this.historyRepo = historyRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public List<JourneyStep> journey(UUID shipmentId) {
        return historyRepo.findByShipmentIdOrderByOccurredAtAsc(shipmentId).stream()
                .map(h -> new JourneyStep(
                        h.getToState(),
                        h.getOccurredAt(),
                        h.getTriggeredBy(),
                        h.getTriggerSource() != null ? h.getTriggerSource().name() : null,
                        h.getNotes()))
                .toList();
    }
}
