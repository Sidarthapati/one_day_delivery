package com.oneday.auth.service.impl;

import com.oneday.auth.domain.User;
import com.oneday.auth.repository.UserRepository;
import com.oneday.common.port.StageContactPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The single {@link StageContactPort} implementation — the hub / GHA desk contact read from the auth
 * user directory by role (+ city for hubs). Mirrors {@link DaDirectoryPortAdapter} for the DA legs.
 */
@Component
class StageContactPortAdapter implements StageContactPort {

    private final UserRepository userRepository;

    StageContactPortAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<Contact> hubDesk(String cityCode) {
        // Prefer the hub operator; fall back to the station manager for that city.
        return firstStaff("HUB_OPERATOR", cityCode)
                .or(() -> firstStaff("STATION_MANAGER", cityCode));
    }

    @Override
    public Optional<Contact> ghaDesk() {
        return firstStaff("AIRLINE_GHA", null);
    }

    private Optional<Contact> firstStaff(String role, String cityCode) {
        return userRepository.findStaffByRoleAndCity(role, cityCode, PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(u -> new Contact(u.getName(), u.getPhone(), u.getRole().getName()));
    }
}
