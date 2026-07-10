package com.oneday.dispatch.service.model;

import com.oneday.dispatch.domain.DaCronAssignment;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-memory ordered queue for one DA, plus the DA's cron meeting for the day. Held in the
 * DaStatusService maps (Phase 2); not persisted directly — {@link DispatchTask}s mirror
 * {@code dispatch_queue} rows.
 */
@Getter
@Setter
public class DaQueue {

    private UUID daId;
    private final List<DispatchTask> tasks = new ArrayList<>();
    /** The DA's cron meeting reference (van rendezvous) loaded at shift start; may be null pre-load. */
    private DaCronAssignment cron;

    public DaQueue() {
    }

    public DaQueue(UUID daId, DaCronAssignment cron) {
        this.daId = daId;
        this.cron = cron;
    }
}
