package com.oneday.shuttle.service;

import com.oneday.airline.service.ShuttleInboundQueryService;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.service.FlightBagService;
import com.oneday.shuttle.config.ShuttleProperties;
import com.oneday.shuttle.domain.ShuttleDirection;
import com.oneday.shuttle.dto.ShuttleQueueResponse;
import com.oneday.shuttle.dto.ShuttleQueueResponse.InboundFlight;
import com.oneday.shuttle.dto.ShuttleQueueResponse.OutboundBag;
import com.oneday.shuttle.repository.ShuttleLegRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the agent's city's <b>shared</b> queue: OPEN+SEALED bags to carry out (with cutoff + leave-by
 * + status chip), and LANDED-and-uncollected flights to bring in. Identical for every agent in the
 * city; an item leaves the list the moment any agent completes it (bag → DISPATCHED / AWB → collected).
 */
@Service
public class ShuttleQueueService {

    private final FlightBagService flightBagService;
    private final ShuttleInboundQueryService inboundQuery;
    private final ShuttleLegRepository legRepository;
    private final ShuttleProperties properties;
    private final Clock clock;

    public ShuttleQueueService(FlightBagService flightBagService, ShuttleInboundQueryService inboundQuery,
                               ShuttleLegRepository legRepository, ShuttleProperties properties, Clock clock) {
        this.flightBagService = flightBagService;
        this.inboundQuery = inboundQuery;
        this.legRepository = legRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ShuttleQueueResponse queue(String cityCode) {
        Instant now = clock.instant();
        String hub = HubCode.of(cityCode);   // users.city_id ("delhi"/"DEL") → hub code ("DEL")

        // Outbound = bags at this hub still waiting to go out (OPEN/SEALED); inbound = landed AWBs not
        // yet collected. Both are STATE questions, never "today's" — anything still pending shows until
        // it's handled, regardless of when the flight was.
        List<OutboundBag> outbound = flightBagService.bagsForOriginHub(hub).stream()
                .map(b -> toOutbound(b, now))
                .sorted(Comparator.comparing(OutboundBag::leaveBy,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<InboundFlight> inbound = inboundQuery.landedInboundAwbs(hub).stream()
                .filter(a -> !legRepository.existsByAwbIdAndDirection(a.awbId(), ShuttleDirection.INBOUND))
                .map(a -> new InboundFlight(a.awbId(), a.flightNo(), a.flightDate(), a.awbNo(),
                        a.parcelCount(), a.landedAt()))
                .toList();

        return new ShuttleQueueResponse(outbound, inbound);
    }

    private OutboundBag toOutbound(FlightBag b, Instant now) {
        Instant cutoff = b.getBagCutoff();
        Instant leaveBy = cutoff == null ? null
                : cutoff.minus((long) properties.getHubToAirportMinutes() + properties.getLoadBufferMinutes(),
                        ChronoUnit.MINUTES);
        String status = b.getStatus() == FlightBagStatus.OPEN ? "OPEN" : "SEALED";
        if (leaveBy != null && now.isAfter(leaveBy)) {
            status = "OVERDUE";   // still at the hub past its leave-by — the SLA red flag
        }
        return new OutboundBag(b.getId(), b.getFlightNo(), b.getFlightDate(), b.getDestHub(),
                b.getParcelCount(), b.getWeightGrams(), cutoff, leaveBy, status);
    }
}
