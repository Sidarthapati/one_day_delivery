package com.oneday.orders.service.impl;

import com.oneday.common.log.AuditLog;
import com.oneday.orders.domain.RtoAction;
import com.oneday.orders.dto.RtoWorklistItem;
import com.oneday.orders.repository.RtoActionRepository;
import com.oneday.orders.service.RtoWorklistService;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @see RtoWorklistService */
@Service
class RtoWorklistServiceImpl implements RtoWorklistService {

    private static final Logger log = LoggerFactory.getLogger(RtoWorklistServiceImpl.class);
    private static final String OPEN = "OPEN";
    private static final String DONE = "DONE";

    private final RtoActionRepository repository;

    RtoWorklistServiceImpl(RtoActionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void record(String originalRef, String childRef, String returnHubCity, String lane,
                       boolean needsBagPull) {
        // Idempotent per child (child_ref is unique) — a re-mint/replay won't duplicate the item.
        // The check-then-insert isn't raced for the same childRef: record() is only reached from
        // ReturnServiceImpl.initiateReturn, which holds a PESSIMISTIC_WRITE lock on the original shipment
        // and returns early (existing child) on a replay — so two threads can't concurrently mint the
        // same childRef and both reach this insert. The unique constraint is the last-resort backstop.
        if (repository.findByChildRef(childRef).isPresent()) {
            return;
        }
        RtoAction a = new RtoAction();
        a.setOriginalRef(originalRef);
        a.setChildRef(childRef);
        a.setReturnHubCity(returnHubCity);
        a.setLane(lane);
        a.setNeedsBagPull(needsBagPull);
        a.setStatus(OPEN);
        repository.save(a);
        AuditLog.event("rto.worklist_created")
                .kv("originalRef", originalRef).kv("childRef", childRef)
                .kv("hub", returnHubCity).kv("lane", lane).kv("needsBagPull", needsBagPull)
                .log();
    }

    @Override
    @Transactional
    public void closeForChild(String childRef, String doneBy) {
        repository.findByChildRef(childRef)
                .filter(a -> OPEN.equals(a.getStatus()))
                .ifPresent(a -> {
                    a.setStatus(DONE);
                    a.setDoneAt(Instant.now());
                    a.setDoneBy(doneBy);
                    repository.save(a);
                    log.info("RTO worklist item for {} auto-closed ({})", childRef, doneBy);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<RtoWorklistItem> listOpen(String cityScope) {
        List<RtoAction> rows = cityScope == null
                ? repository.findByStatusOrderByCreatedAtDesc(OPEN)
                : repository.findByReturnHubCityAndStatusOrderByCreatedAtDesc(cityScope, OPEN);
        return rows.stream().map(RtoWorklistServiceImpl::toItem).toList();
    }

    @Override
    @Transactional
    public void markDone(UUID id, String cityScope, String userId) {
        RtoAction a = repository.findById(id)
                .filter(x -> cityScope == null || cityScope.equals(x.getReturnHubCity()))
                .orElseThrow(() -> new EntityNotFoundException("RTO worklist item not found: " + id));
        if (OPEN.equals(a.getStatus())) {
            a.setStatus(DONE);
            a.setDoneAt(Instant.now());
            a.setDoneBy(userId);
            repository.save(a);
        }
    }

    private static RtoWorklistItem toItem(RtoAction a) {
        return new RtoWorklistItem(a.getId(), a.getOriginalRef(), a.getChildRef(),
                a.getReturnHubCity(), a.getLane(), a.isNeedsBagPull(), a.getCreatedAt());
    }
}
