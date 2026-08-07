package com.oneday.auth.service.impl;

import com.oneday.auth.repository.DaProfileRepository;
import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
}
