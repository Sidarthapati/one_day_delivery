package com.oneday.shuttle.service;

import com.oneday.airline.service.AirlineCustodyService;
import com.oneday.airline.service.ShuttleInboundQueryService;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.log.AuditLog;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.service.FlightBagService;
import com.oneday.shuttle.domain.ShuttleDirection;
import com.oneday.shuttle.domain.ShuttleLeg;
import com.oneday.shuttle.dto.BagActionResult;
import com.oneday.shuttle.dto.BagActionResult.BagOutcome;
import com.oneday.shuttle.repository.ShuttleLegRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The shuttle's three actions. Each reuses the existing hub/airline backend seam and additionally binds
 * the carried parcels to the acting agent ({@code shuttle_leg}) so tracking shows the right dot. Shared
 * completion is automatic: dispatch flips the shared bag row, collect writes the shared leg — the item
 * then drops from every agent's queue.
 */
@Service
public class ShuttleActionService {

    private static final Logger log = LoggerFactory.getLogger(ShuttleActionService.class);

    private final FlightBagService flightBagService;
    private final AirlineCustodyService airlineCustodyService;
    private final ShuttleInboundQueryService inboundQuery;
    private final ShuttleLegRepository legRepository;

    public ShuttleActionService(FlightBagService flightBagService, AirlineCustodyService airlineCustodyService,
                                ShuttleInboundQueryService inboundQuery, ShuttleLegRepository legRepository) {
        this.flightBagService = flightBagService;
        this.airlineCustodyService = airlineCustodyService;
        this.inboundQuery = inboundQuery;
        this.legRepository = legRepository;
    }

    /** Batch several sealed bags onto one trip; a non-ready/already-taken bag is skipped, not fatal. */
    @Transactional
    public BagActionResult outToAirport(UUID agentId, String cityCode, List<UUID> bagIds) {
        String hub = HubCode.of(cityCode);
        List<BagOutcome> results = new ArrayList<>();
        for (UUID bagId : bagIds) {
            try {
                FlightBag bag = flightBagService.bag(bagId);
                if (!hub.equals(bag.getOriginHub())) {
                    results.add(BagOutcome.skipped(bagId, "WRONG_CITY"));
                    continue;
                }
                List<FlightBagService.BagParcelInfo> parcels = flightBagService.parcelsFor(bagId);
                flightBagService.dispatch(bagId);   // throws if not SEALED / already dispatched
                parcels.forEach(p -> {
                    legRepository.save(ShuttleLeg.builder()
                            .parcelId(p.parcelId()).agentId(agentId)
                            .direction(ShuttleDirection.OUTBOUND).bagId(bagId).build());
                    // Per-parcel line so the whole leg is queryable by parcelId (who carried it, which way).
                    AuditLog.event("shuttle.parcel_bound")
                            .kv("parcelId", p.parcelId()).kv("shipmentRef", p.shipmentRef())
                            .kv("agentId", agentId).kv("direction", "OUTBOUND")
                            .kv("bagId", bagId).kv("flightNo", bag.getFlightNo()).log();
                });
                AuditLog.event("shuttle.out_to_airport")
                        .kv("agentId", agentId).kv("bagId", bagId)
                        .kv("flightNo", bag.getFlightNo()).kv("parcelCount", parcels.size()).log();
                results.add(BagOutcome.dispatched(bagId, parcels.size()));
            } catch (RuntimeException e) {
                // Non-SEALED / already-dispatched (the other agent took it) / unknown bag — skip it.
                log.debug("out-to-airport skipped bag {}: {}", bagId, e.getMessage());
                results.add(BagOutcome.skipped(bagId, "NOT_READY"));
            }
        }
        return new BagActionResult(results);
    }

    /** Ask the hub to seal an OPEN bag early so it can be batched (badges the hub console). */
    @Transactional
    public void requestSeal(UUID agentId, String cityCode, UUID bagId) {
        FlightBag bag = flightBagService.bag(bagId);
        if (!HubCode.of(cityCode).equals(bag.getOriginHub())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bag is not in your city");
        }
        flightBagService.requestSeal(bagId);
        AuditLog.event("shuttle.seal_requested").kv("agentId", agentId).kv("bagId", bagId).log();
    }

    /** Collected a landed flight's parcels from the airport → each parcel goes DISPATCHED_TO_HUB. */
    @Transactional
    public int collectFromAirport(UUID agentId, UUID awbId) {
        List<UUID> parcelIds = inboundQuery.parcelIdsForAwb(awbId);
        int scanned = airlineCustodyService.record(awbId, ScanEventType.DEST_SHUTTLE_IN);
        if (scanned == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No parcels on AWB " + awbId);
        }
        parcelIds.forEach(pid -> {
            legRepository.save(ShuttleLeg.builder()
                    .parcelId(pid).agentId(agentId)
                    .direction(ShuttleDirection.INBOUND).awbId(awbId).build());
            // Per-parcel line so the whole leg is queryable by parcelId (who carried it, which way).
            AuditLog.event("shuttle.parcel_bound")
                    .kv("parcelId", pid).kv("agentId", agentId)
                    .kv("direction", "INBOUND").kv("awbId", awbId).log();
        });
        AuditLog.event("shuttle.collect_from_airport")
                .kv("agentId", agentId).kv("awbId", awbId).kv("parcelCount", scanned).log();
        return scanned;
    }
}
