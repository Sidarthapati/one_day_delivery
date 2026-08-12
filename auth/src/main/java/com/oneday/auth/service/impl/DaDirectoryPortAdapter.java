package com.oneday.auth.service.impl;

import com.oneday.auth.repository.DaProfileRepository;
import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The single {@link DaDirectoryPort} implementation — the real DA roster read from auth. */
@Component
class DaDirectoryPortAdapter implements DaDirectoryPort {

    private final DaProfileRepository daProfileRepository;

    DaDirectoryPortAdapter(DaProfileRepository daProfileRepository) {
        this.daProfileRepository = daProfileRepository;
    }

    @Override
    public List<UUID> availableDaIds(String cityId, LocalDate date, Shift shift) {
        return daProfileRepository.findAvailableDaIds(cityId, shift.name(), date);
    }

    @Override
    public Map<UUID, DaContact> contactsFor(Collection<UUID> daIds) {
        if (daIds.isEmpty()) {
            return Map.of();
        }
        return daProfileRepository.findContactsByDaIds(daIds).stream()
                .collect(Collectors.toMap(
                        DaProfileRepository.DaContactRow::getDaId,
                        row -> new DaContact(row.getName(), row.getPhone())));
    }
}
