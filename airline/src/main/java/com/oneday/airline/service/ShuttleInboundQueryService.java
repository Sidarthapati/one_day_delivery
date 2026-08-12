package com.oneday.airline.service;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbParcel;
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
 * Public read for the M12 shuttle inbound queue: the AWBs arriving at a destination hub on a date whose
 * flight has actually <b>LANDED</b> (per the M9 status poll). The shuttle module subtracts the ones it
 * has already collected. Dynamic by construction — a flight that lands mid-drive simply starts matching.
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
    public List<InboundAwb> landedInboundAwbs(String destHub, LocalDate date) {
        return awbRepository.findByDestHubAndFlightDate(destHub, date).stream()
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
