package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.domain.AttendanceConfig;
import com.oneday.dispatch.repository.AttendanceConfigRepository;
import com.oneday.dispatch.service.AttendanceConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Default {@link AttendanceConfigService}. The single config row is seeded by Flyway (V5_18); the
 * {@code auto_present_enabled} flag is cached in memory so {@link #isAutoPresentEnabled()} stays cheap
 * on the per-ping path and is refreshed whenever an admin writes.
 */
@Service
class AttendanceConfigServiceImpl implements AttendanceConfigService {

    private final AttendanceConfigRepository repository;

    /** Cached flag; null until first load. Volatile — written by the admin thread, read by ping threads. */
    private volatile Boolean cachedAutoPresent;

    AttendanceConfigServiceImpl(AttendanceConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isAutoPresentEnabled() {
        Boolean cached = cachedAutoPresent;
        if (cached != null) {
            return cached;
        }
        boolean value = load().isAutoPresentEnabled();
        cachedAutoPresent = value;
        return value;
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceConfig get() {
        return load();
    }

    @Override
    @Transactional
    public AttendanceConfig setAutoPresentEnabled(boolean enabled, UUID actorUserId) {
        AttendanceConfig config = load();
        config.setAutoPresentEnabled(enabled);
        config.setUpdatedByUserId(actorUserId);
        AttendanceConfig saved = repository.save(config);
        cachedAutoPresent = enabled;
        return saved;
    }

    /** The singleton row; defensively creates it if a DB somehow lacks the seed. */
    private AttendanceConfig load() {
        return repository.findFirstByOrderByCreatedAtAsc().orElseGet(() -> {
            AttendanceConfig config = new AttendanceConfig();
            config.setAutoPresentEnabled(true);
            return repository.save(config);
        });
    }
}
