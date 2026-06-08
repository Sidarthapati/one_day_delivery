package com.oneday.routing.service.model;

import java.util.List;
import java.util.UUID;

/** One hex of a DA's territory: its demand snapshot for the day + its corner vertices (§7.1). */
public record TerritoryHex(
        UUID hexId,
        long h3Index,
        double demandScoreOrders,
        double serviceTimeMin,
        List<MeetingVertex> vertices
) {}
