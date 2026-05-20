package com.oneday.common.port;

import com.oneday.common.port.dto.EtaRequest;
import com.oneday.common.port.dto.EtaResult;

/**
 * Implemented by M9 (airline module).
 * M4 calls this at BOOKED (to set etaPromised) and at AT_ORIGIN_HUB (to recalculate
 * once the parcel is checked in and a flight is likely assigned). AT_ORIGIN_HUB is reached
 * via two paths: HUB_ORIGIN_IN scan (DA_PICKUP) and SELF_DROP_ACCEPTED scan (SELF_DROP) —
 * EtaPort is called on both.
 * M9 uses currentState and EtaContext to select the appropriate estimation model.
 * M4 stores whatever M9 returns — all ETA logic and edge cases are M9's responsibility.
 */
public interface EtaPort {
    EtaResult fetchEta(EtaRequest request);
}
