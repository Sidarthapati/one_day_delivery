package com.oneday.common.domain.enums;

/**
 * Who an SLA escalation is routed to (M10, PRD RACI). RED goes to the city's Supervisor /
 * Station Manager; a confirmed breach (or a lane with repeated RED) is raised to Admin / control tower.
 */
public enum EscalationLevel {
    SUPERVISOR,
    STATION_MANAGER,
    ADMIN
}
