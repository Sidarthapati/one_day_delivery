package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.domain.FlightInstanceStatus;
import com.oneday.airline.domain.FlightSchedule;
import com.oneday.airline.domain.LaneRateCard;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import com.oneday.airline.repository.FlightScheduleRepository;
import com.oneday.airline.repository.LaneRateCardRepository;
import com.oneday.airline.service.AwbBookingService;
import com.oneday.airline.service.exception.FlightScheduleNotFoundException;
import com.oneday.airline.service.exception.LaneRateCardNotFoundException;
import com.oneday.airline.service.provider.FlightProviderPort;
import com.oneday.hub.service.FlightBagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Books a hub's sealed flight bag onto a flight as one confirmed reservation (§6). {@code
 * flight_instance} is created lazily here on the first bag booked for a (flight, date) — mirrors
 * M7's lazy bag-open pattern — and its running weight commitment is incremented under a row lock so
 * concurrent bag seals on the same flight never lose an update.
 */
@Service
class AwbBookingServiceImpl implements AwbBookingService {

    private static final Logger log = LoggerFactory.getLogger(AwbBookingServiceImpl.class);

    private final AwbRepository awbRepository;
    private final AwbParcelRepository awbParcelRepository;
    private final FlightInstanceRepository flightInstanceRepository;
    private final FlightScheduleRepository flightScheduleRepository;
    private final LaneRateCardRepository laneRateCardRepository;
    private final FlightProviderPort flightProviderPort;
    private final FlightBagService flightBagService;
    private final CostEstimator costEstimator;
    private final AirlineProperties properties;

    AwbBookingServiceImpl(AwbRepository awbRepository, AwbParcelRepository awbParcelRepository,
                           FlightInstanceRepository flightInstanceRepository, FlightScheduleRepository flightScheduleRepository,
                           LaneRateCardRepository laneRateCardRepository, FlightProviderPort flightProviderPort,
                           FlightBagService flightBagService, CostEstimator costEstimator, AirlineProperties properties) {
        this.awbRepository = awbRepository;
        this.awbParcelRepository = awbParcelRepository;
        this.flightInstanceRepository = flightInstanceRepository;
        this.flightScheduleRepository = flightScheduleRepository;
        this.laneRateCardRepository = laneRateCardRepository;
        this.flightProviderPort = flightProviderPort;
        this.flightBagService = flightBagService;
        this.costEstimator = costEstimator;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Awb book(BookBagCommand command) {
        return awbRepository.findByBagIdAndStatus(command.bagId(), AwbStatus.BOOKED)
                .orElseGet(() -> createBooking(command));
    }

    private Awb createBooking(BookBagCommand command) {
        FlightInstance instance = findOrCreateInstance(command.flightNo(), command.flightDate());
        instance.setBookedWeightGrams(instance.getBookedWeightGrams() + command.weightGrams());
        flightInstanceRepository.save(instance);

        LaneRateCard rateCard = laneRateCardRepository
                .findByOriginHubAndDestHubAndStatus(instance.getOriginHub(), instance.getDestHub(), "ACTIVE")
                .orElseThrow(() -> new LaneRateCardNotFoundException(instance.getOriginHub(), instance.getDestHub()));
        boolean overnight = properties.isOvernight(instance.getDeparture().atZone(ClockConfig.IST).toLocalTime());
        long costPaise = costEstimator.estimatePaise(rateCard, command.weightGrams(), overnight);

        FlightProviderPort.BookingConfirmation confirmation = flightProviderPort.book(
                instance.getFlightNo(), instance.getFlightDate(), instance.getOriginHub(), instance.getDestHub(),
                command.weightGrams(), command.parcelCount());

        Awb awb = new Awb();
        awb.setAwbNo(generateAwbNo(instance, command.bagId()));
        awb.setFlightNo(instance.getFlightNo());
        awb.setFlightDate(instance.getFlightDate());
        awb.setOriginHub(instance.getOriginHub());
        awb.setDestHub(instance.getDestHub());
        awb.setBagId(command.bagId());
        awb.setTotalWeightGrams(command.weightGrams());
        awb.setParcelCount(command.parcelCount());
        awb.setCostPaise(costPaise);
        awb.setProviderRef(confirmation.providerRef());
        awb.setStatus(AwbStatus.BOOKED);
        Awb saved = awbRepository.save(awb);
        writeParcelLines(saved, command.bagId());
        return saved;
    }

    /**
     * Per-parcel linkage (§6): one {@link AwbParcel} row per parcel in the bag, its cost share
     * proportional to its own weight (§10) — not a flat even split. Floors each share, then hands the
     * rounding remainder to the heaviest parcel so the lines sum exactly to {@code awb.costPaise}.
     */
    private void writeParcelLines(Awb awb, UUID bagId) {
        List<FlightBagService.BagParcelInfo> parcels = flightBagService.parcelsFor(bagId);
        if (parcels.isEmpty()) {
            log.warn("No parcels found for bag {} while writing AWB {} parcel lines — skipping", bagId, awb.getId());
            return;
        }

        long total = awb.getCostPaise();
        int totalWeight = awb.getTotalWeightGrams();
        List<AwbParcel> lines = parcels.stream().map(p -> {
            AwbParcel line = new AwbParcel();
            line.setAwbId(awb.getId());
            line.setParcelId(p.parcelId());
            line.setShipmentRef(p.shipmentRef());
            line.setWeightGrams(p.weightGrams());
            line.setAllocatedCostPaise(totalWeight > 0 ? total * p.weightGrams() / totalWeight : 0);
            return line;
        }).toList();

        long remainder = total - lines.stream().mapToLong(AwbParcel::getAllocatedCostPaise).sum();
        if (remainder != 0) {
            lines.stream()
                    .max(Comparator.comparingInt(AwbParcel::getWeightGrams))
                    .ifPresent(heaviest -> heaviest.setAllocatedCostPaise(heaviest.getAllocatedCostPaise() + remainder));
        }
        awbParcelRepository.saveAll(lines);
    }

    private FlightInstance findOrCreateInstance(String flightNo, LocalDate flightDate) {
        return flightInstanceRepository.findByFlightNoAndFlightDateForUpdate(flightNo, flightDate)
                .orElseGet(() -> createInstance(flightNo, flightDate));
    }

    private FlightInstance createInstance(String flightNo, LocalDate flightDate) {
        FlightSchedule schedule = flightScheduleRepository.findByFlightNo(flightNo)
                .orElseThrow(() -> new FlightScheduleNotFoundException(flightNo));

        ZonedDateTime departure = flightDate.atTime(schedule.getDepartureTime()).atZone(ClockConfig.IST);
        ZonedDateTime arrival = flightDate.atTime(schedule.getArrivalTime()).atZone(ClockConfig.IST);
        if (!schedule.getArrivalTime().isAfter(schedule.getDepartureTime())) {
            arrival = arrival.plusDays(1);   // overnight-spanning flight
        }
        Instant cutoff = departure.minusMinutes(properties.getGateCutoffLeadMinutes()).toInstant();

        FlightInstance instance = new FlightInstance();
        instance.setFlightNo(flightNo);
        instance.setFlightDate(flightDate);
        instance.setOriginHub(schedule.getOriginHub());
        instance.setDestHub(schedule.getDestHub());
        instance.setDeparture(departure.toInstant());
        instance.setArrival(arrival.toInstant());
        instance.setCutoff(cutoff);
        instance.setCapacityKg(schedule.getCapacityKg());
        instance.setBookedWeightGrams(0);
        instance.setStatus(FlightInstanceStatus.SCHEDULED);
        return instance;   // unsaved — createBooking's single save persists it once, weight already added
    }

    /** Our own record identifier, distinct from the vendor's providerRef; bagId suffix guarantees uniqueness. */
    private String generateAwbNo(FlightInstance instance, UUID bagId) {
        return "AWB-%s-%s-%s".formatted(instance.getFlightNo(), instance.getFlightDate(),
                bagId.toString().substring(0, 8));
    }
}
