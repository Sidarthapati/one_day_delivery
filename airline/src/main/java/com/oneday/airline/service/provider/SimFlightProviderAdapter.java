package com.oneday.airline.service.provider;

import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.domain.FlightSchedule;
import com.oneday.airline.repository.FlightScheduleRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Realistic simulated stand-in for a real flight-booking vendor (§4), the default until one is
 * signed. Candidates are derived from our own {@code flight_schedule} timetable rather than a live
 * network call; booking returns a deterministic, believable confirmation number instead of a real
 * PNR/AWB reference.
 */
@Component
class SimFlightProviderAdapter implements FlightProviderPort {

    private final FlightScheduleRepository flightScheduleRepository;

    SimFlightProviderAdapter(FlightScheduleRepository flightScheduleRepository) {
        this.flightScheduleRepository = flightScheduleRepository;
    }

    @Override
    public List<FlightCandidate> search(String originHub, String destHub, LocalDate date) {
        return flightScheduleRepository.findByOriginHubAndDestHubAndActiveTrue(originHub, destHub).stream()
                .filter(schedule -> runsOn(schedule, date))
                .map(schedule -> new FlightCandidate(schedule.getFlightNo(), schedule.getCarrier(),
                        schedule.getDepartureTime(), schedule.getArrivalTime(), schedule.getCapacityKg()))
                .toList();
    }

    @Override
    public BookingConfirmation book(String flightNo, LocalDate flightDate, String originHub, String destHub,
                                     int weightGrams, int parcelCount) {
        // Deterministic-looking confirmation; a real vendor would return its own PNR/booking ref.
        String ref = "SIM-%s-%s-%s".formatted(flightNo, flightDate, UUID.randomUUID().toString().substring(0, 8));
        return new BookingConfirmation(ref);
    }

    @Override
    public FlightStatusResult status(String flightNo, LocalDate flightDate) {
        FlightSchedule schedule = flightScheduleRepository.findByFlightNo(flightNo)
                .orElseThrow(() -> new IllegalArgumentException("Unknown flight number: " + flightNo));

        ZonedDateTime departure = flightDate.atTime(schedule.getDepartureTime()).atZone(ClockConfig.IST);
        ZonedDateTime arrival = flightDate.atTime(schedule.getArrivalTime()).atZone(ClockConfig.IST);
        if (!schedule.getArrivalTime().isAfter(schedule.getDepartureTime())) {
            arrival = arrival.plusDays(1);
        }

        // Deterministic on (flightNo, flightDate) — repeated polls of the same flight must agree,
        // never flip-flop between outcomes. Mostly ON_TIME; a small injected slice of DELAYED/CANCELLED
        // for demo/testing realism.
        int bucket = Math.floorMod((flightNo + flightDate).hashCode(), 100);
        if (bucket < 3) {
            return new FlightStatusResult(FlightRealWorldStatus.CANCELLED, departure.toInstant(), arrival.toInstant());
        }
        if (bucket < 10) {
            long delayMinutes = 30 + Math.floorMod(bucket, 90);
            return new FlightStatusResult(FlightRealWorldStatus.DELAYED,
                    departure.plusMinutes(delayMinutes).toInstant(), arrival.plusMinutes(delayMinutes).toInstant());
        }
        return new FlightStatusResult(FlightRealWorldStatus.ON_TIME, departure.toInstant(), arrival.toInstant());
    }

    private boolean runsOn(FlightSchedule schedule, LocalDate date) {
        int bit = date.getDayOfWeek().getValue() - 1;   // Monday=0 .. Sunday=6
        return (schedule.getDaysOfWeek() & (1 << bit)) != 0;
    }
}
