package com.oneday.dispatch.service.model;

import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * In-memory view of a queued task (NOT a JPA entity). Lives inside a {@link DaQueue} on the hot path;
 * mutable because position / status / ETA change as the queue is re-sequenced and worked.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DispatchTask {
    private UUID daId;
    private UUID shipmentId;
    private TaskType taskType;
    private double taskLat;
    private double taskLon;
    private int queuePosition;
    private TaskStatus status;
    private Instant expectedEta;
}
