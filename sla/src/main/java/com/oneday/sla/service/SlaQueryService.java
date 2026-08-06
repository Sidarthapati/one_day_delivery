package com.oneday.sla.service;

import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.dto.SlaControlTowerResponse;
import com.oneday.sla.dto.SlaEscalationView;
import com.oneday.sla.dto.SlaPassRateResponse;
import com.oneday.sla.dto.SlaShipmentDetailResponse;

import java.time.Instant;
import java.util.List;

/**
 * Read side of M10 for the station-manager / admin control tower. {@code cityScope == null} means
 * admin (all cities); a non-null value restricts to a city (matched on origin or destination).
 */
public interface SlaQueryService {

    SlaControlTowerResponse controlTower(SlaState state, String cityScope, int page, int size);

    SlaShipmentDetailResponse detail(String shipmentRef, String cityScope);

    List<SlaEscalationView> redQueue(String cityScope);

    SlaPassRateResponse passRate(Instant from, Instant to, String cityScope);

    void acknowledge(java.util.UUID escalationId, String cityScope, String userId, String role, String notes);

    void resolve(java.util.UUID escalationId, String cityScope, String userId, String role, String notes);
}
