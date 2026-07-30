package com.oneday.hub.dto;

import com.oneday.hub.domain.Stand;
import com.oneday.hub.domain.StandStatus;

import java.util.UUID;

/** A physical stand on the hub floor (§14.3) — the directory reassign-stand and the floor view need. */
public record StandResponse(
        UUID id,
        UUID hubId,
        String standNo,
        String zone,
        int capacity,
        StandStatus status) {

    public static StandResponse from(Stand s) {
        return new StandResponse(s.getId(), s.getHubId(), s.getStandNo(), s.getZone(), s.getCapacity(), s.getStatus());
    }
}
