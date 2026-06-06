package com.oneday.grid.adapter;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.port.ServiceabilityPort;
import com.oneday.common.port.dto.ServiceabilityQuery;
import com.oneday.common.port.dto.ServiceabilityResult;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.service.GridService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * M3's implementation of the M4 serviceability port (the real grid, not a stub).
 * <p>
 * When a leg carries map coordinates, they are resolved straight to an H3 hex via
 * {@link GridService#serviceableAt}, so any point inside a city's grid is serviceable.
 * When only a pincode is known, it falls back to the city's pincode catalogue.
 * A route is serviceable only when both legs resolve; delivery type and the origin
 * tile (for M5 DA assignment) come from the resolved hexes.
 */
@Component
class GridServiceabilityAdapter implements ServiceabilityPort {

    // Pincode 3-digit prefix → grid cityCode, for the no-coordinates fallback path.
    private static final Map<String, String> PREFIX_TO_CITY = Map.of(
            "560", "bangalore",
            "400", "mumbai",
            "110", "delhi",
            "600", "chennai",
            "500", "hyderabad"
    );

    private final GridService gridService;

    GridServiceabilityAdapter(GridService gridService) {
        this.gridService = gridService;
    }

    @Override
    public ServiceabilityResult check(ServiceabilityQuery q) {
        Leg origin = resolve(q.originLat(), q.originLon(), q.originPincode());
        Leg dest   = resolve(q.destLat(), q.destLon(), q.destPincode());

        if (!origin.serviceable() || !dest.serviceable()) {
            return new ServiceabilityResult(false, null, null, null);
        }
        DeliveryType type = origin.cityId().equals(dest.cityId())
                ? DeliveryType.SAME_CITY
                : DeliveryType.INTERCITY;
        return new ServiceabilityResult(true, origin.hexId(), dest.hexId(), type);
    }

    private Leg resolve(Double lat, Double lon, String pincode) {
        if (lat != null && lon != null) {
            ServiceableAtResponse r = gridService.serviceableAt(lat, lon);
            return new Leg(r.serviceable(), r.cityId(), r.hexId());
        }
        String cityCode = pincode != null && pincode.length() >= 3
                ? PREFIX_TO_CITY.get(pincode.substring(0, 3))
                : null;
        if (cityCode == null) {
            return Leg.NONE;
        }
        UUID cityId = gridService.resolveCityId(cityCode);
        ServiceabilityResponse r = gridService.checkServiceability(cityId, pincode);
        return new Leg(r.serviceable(), cityId, r.hexId());
    }

    private record Leg(boolean serviceable, UUID cityId, UUID hexId) {
        static final Leg NONE = new Leg(false, null, null);
    }
}
