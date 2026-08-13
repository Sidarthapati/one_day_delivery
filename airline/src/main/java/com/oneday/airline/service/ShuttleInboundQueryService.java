package com.oneday.airline.service;

import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.domain.FlightInstanceStatus;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Public read for the M12 shuttle inbound queue: every live booking to a destination hub whose flight
 * has <b>LANDED</b> and which has <b>not yet been collected</b> from the airport (its
 * {@code dest_collected_at} is null). No date filter — a bag that landed yesterday and still hasn't been
 * brought to the hub must still be collected today. Dynamic by construction: a flight that lands
 * mid-drive simply starts matching, and an AWB drops off the moment any agent collects it.
 */
@Service
public class ShuttleInboundQueryService {

    private final AwbRepository awbRepository;
    private final AwbParcelRepository awbParcelRepository;
    private final FlightInstanceRepository flightInstanceRepository;

    public ShuttleInboundQueryService(AwbRepository awbRepository,
                                      AwbParcelRepository awbParcelRepository,
                                      FlightInstanceRepository flightInstanceRepository) {
        this.awbRepository = awbRepository;
        this.awbParcelRepository = awbParcelRepository;
        this.flightInstanceRepository = flightInstanceRepository;
    }

    /** The parcel ids on an AWB — the shuttle writes one INBOUND leg per parcel when it collects. */
    @Transactional(readOnly = true)
    public List<UUID> parcelIdsForAwb(UUID awbId) {
        return awbParcelRepository.findByAwbId(awbId).stream()
                .map(AwbParcel::getParcelId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InboundAwb> landedInboundAwbs(String destHub) {
        return awbRepository.findByDestHubAndStatusAndDestCollectedAtIsNull(destHub, AwbStatus.BOOKED).stream()
                .map(a -> flightInstanceRepository
                        .findByFlightNoAndFlightDate(a.getFlightNo(), a.getFlightDate())
                        .filter(fi -> fi.getStatus() == FlightInstanceStatus.LANDED)
                        .map(fi -> new InboundAwb(a.getId(), a.getFlightNo(), a.getFlightDate(),
                                a.getAwbNo(), a.getParcelCount(), fi.getArrival()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** One landed inbound AWB the shuttle can collect from the airport. */
    public record InboundAwb(UUID awbId, String flightNo, LocalDate flightDate, String awbNo,
                             int parcelCount, Instant landedAt) {
    }
}
