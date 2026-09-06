package com.oneday.dispatch.dto.response;

import com.oneday.dispatch.domain.DaDisposition;
import com.oneday.dispatch.domain.DispositionCategory;
import com.oneday.dispatch.domain.DispositionReason;
import com.oneday.dispatch.domain.DispositionStatus;

import java.time.Instant;
import java.util.UUID;

/** One disposition, as the driver app / station console see it. */
public record DispositionResponse(
        UUID id,
        UUID daId,
        DispositionCategory category,
        DispositionReason reason,
        DispositionStatus status,
        String note,
        Instant scheduledStart,
        Instant scheduledEnd,
        Instant actualStart,
        Instant actualEnd,
        Integer durationMinutes,
        int escalationLevel) {

    public static DispositionResponse of(DaDisposition d) {
        return new DispositionResponse(
                d.getId(), d.getDaId(), d.getCategory(), d.getReason(), d.getStatus(), d.getNote(),
                d.getScheduledStart(), d.getScheduledEnd(), d.getActualStart(), d.getActualEnd(),
                d.getDurationMinutes(), d.getEscalationLevel());
    }
}
