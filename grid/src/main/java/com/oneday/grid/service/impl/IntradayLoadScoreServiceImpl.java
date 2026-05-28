package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.dto.response.TileLoadScoreResponse;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
class IntradayLoadScoreServiceImpl implements IntradayLoadScoreService {

    private final ConcurrentHashMap<UUID, Integer> unservedByHex = new ConcurrentHashMap<>();
    private final GridProperties properties;

    IntradayLoadScoreServiceImpl(GridProperties properties) {
        this.properties = properties;
    }

    @Override
    public TileLoadScoreResponse getLoadScore(UUID tileId, LocalDate date) {
        int unserved = unservedByHex.getOrDefault(tileId, 0);
        // adjustedLoadScore uses raw unserved count until M4 provides expected-by-now data
        double adjustedLoadScore = unserved;
        return new TileLoadScoreResponse(tileId, date, unserved, adjustedLoadScore, severity(adjustedLoadScore));
    }

    @Override
    public void updateQueueDepth(UUID cityId, LocalDate date, Map<UUID, Integer> unservedByHexMap) {
        unservedByHex.putAll(unservedByHexMap);
    }

    @Override
    public void resetForShift() {
        unservedByHex.clear();
    }

    private String severity(double score) {
        if (score >= properties.getIntraday().getOverloadCriticalThreshold()) return "CRITICAL";
        if (score >= properties.getIntraday().getOverloadWarningThreshold()) return "WARNING";
        return "OK";
    }
}
