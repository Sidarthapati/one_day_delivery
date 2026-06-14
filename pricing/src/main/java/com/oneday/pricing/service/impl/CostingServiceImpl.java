package com.oneday.pricing.service.impl;

import com.oneday.common.port.dto.CostFloorQuery;
import com.oneday.common.port.dto.CostFloorResult;
import com.oneday.pricing.domain.CostingParams;
import com.oneday.pricing.repository.CostingParamsRepository;
import com.oneday.pricing.service.CityCodes;
import com.oneday.pricing.service.CostingService;
import com.oneday.pricing.service.NoRateConfiguredException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-parcel cost floor = DA pickup share + van share + hub + airline. The DA share divides the
 * shift cost by <i>effective</i> capacity (nameplate parcels × utilisation), so the ~70% utilisation
 * target raises the floor rather than pretending a DA is busy 100% of the shift (see the DA-utilisation
 * invariant). Internal only — never returned to customers.
 */
@Service
class CostingServiceImpl implements CostingService {

    private final CostingParamsRepository repo;

    CostingServiceImpl(CostingParamsRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public CostFloorResult computeCostFloor(CostFloorQuery query) {
        String city = CityCodes.normalise(query.city());
        CostingParams p = repo.findFirstByCityAndStatus(city, "ACTIVE").orElseThrow(() ->
                new NoRateConfiguredException("No active costing params for city " + city));

        double effectiveParcels = p.getAvgParcelsPerShift() * (p.getUtilisationPct() / 100.0);
        long daShare = effectiveParcels <= 0 ? 0
                : Math.round(p.getDaCostPerShiftPaise() / effectiveParcels);
        long vanShare = p.getAvgParcelsPerVanRun() <= 0 ? 0
                : Math.round((double) p.getVanCostPerRunPaise() / p.getAvgParcelsPerVanRun());
        long hub = p.getHubCostPerParcelPaise();
        long airline = p.getAirlineCostPerParcelPaise();
        long total = daShare + vanShare + hub + airline;

        Map<String, Long> breakdown = new LinkedHashMap<>();
        breakdown.put("da_pickup", daShare);
        breakdown.put("van", vanShare);
        breakdown.put("hub", hub);
        breakdown.put("airline", airline);

        return new CostFloorResult(city, total, breakdown, p.getVersion());
    }
}
