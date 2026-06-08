package com.oneday.routing.service;

import com.oneday.routing.service.model.DaTerritory;
import com.oneday.routing.service.model.MeetingPlan;

import java.util.List;

/**
 * Stage 2 of the nightly pipeline (§7.2, M6-D-001): choose the meeting vertices. This is a
 * set-cover / p-median problem solved <i>before</i> the VRP — pick the fewest hex-corner vertices
 * such that every active DA territory has a reachable meeting point, biasing toward vertices shared
 * by several territories so one van stop serves multiple DAs.
 */
public interface MeetingPointSelectionService {

    /** Select meeting vertices covering every DA in {@code territories}. */
    MeetingPlan select(List<DaTerritory> territories);
}
