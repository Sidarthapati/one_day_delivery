package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.ShipmentTrackResponse;
import com.oneday.orders.dto.ShipmentTrackResponse.Eta;
import com.oneday.orders.dto.ShipmentTrackResponse.Location;
import com.oneday.orders.dto.ShipmentTrackResponse.Route;
import com.oneday.orders.dto.ShipmentTrackResponse.Waypoint;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.service.TrackingService;
import com.oneday.orders.tracking.CityNodeCatalog;
import com.oneday.orders.tracking.CityNodeCatalog.Coord;
import com.oneday.orders.tracking.LocationResolver;
import com.oneday.orders.tracking.LocationResolver.ResolvedLocation;
import com.oneday.orders.tracking.MilestoneBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * @see TrackingService
 */
@Service
class TrackingServiceImpl implements TrackingService {

    private final ShipmentRepository shipmentRepository;
    private final CustomerVisibleStateMapper stateMapper;
    private final LocationResolver locationResolver;
    private final MilestoneBuilder milestoneBuilder;
    private final CityNodeCatalog cities;

    TrackingServiceImpl(ShipmentRepository shipmentRepository,
                        CustomerVisibleStateMapper stateMapper,
                        LocationResolver locationResolver,
                        MilestoneBuilder milestoneBuilder,
                        CityNodeCatalog cities) {
        this.shipmentRepository = shipmentRepository;
        this.stateMapper = stateMapper;
        this.locationResolver = locationResolver;
        this.milestoneBuilder = milestoneBuilder;
        this.cities = cities;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentTrackResponse> track(String userId, String shipmentRef) {
        UUID id = UserIds.parse(userId);
        if (id == null) {
            return Optional.empty();
        }
        return shipmentRepository.findByShipmentRef(shipmentRef)
                .filter(s -> id.equals(s.getBookedByUserId()))   // ownership scope: not yours → not found
                .map(this::toResponse);
    }

    private ShipmentTrackResponse toResponse(Shipment s) {
        String stateLabel = stateMapper.labelFor(s.getState());
        ResolvedLocation r = locationResolver.resolve(s);
        Location location = new Location(
                r.kind(), r.lat(), r.lon(), r.live(), r.moving(), stateLabel, r.lastUpdatedAt(), r.minutesLate());

        List<ShipmentTrackResponse.Milestone> milestones = milestoneBuilder.build(s).stream()
                .map(m -> new ShipmentTrackResponse.Milestone(m.label(), m.occurredAt(), m.done()))
                .toList();

        return new ShipmentTrackResponse(
                s.getShipmentRef(), s.getState(), stateLabel, location, route(s), milestones,
                new Eta(s.getEtaPromised(), s.getEtaUpdated()));
    }

    private Route route(Shipment s) {
        List<Waypoint> waypoints = new ArrayList<>();
        Consumer<Waypoint> add = waypoints::add;
        cities.hub(s.getOriginCity()).ifPresent(c -> add.accept(waypoint(c, "Origin hub")));
        if (s.getDeliveryType() == DeliveryType.INTERCITY) {
            cities.airport(s.getOriginCity()).ifPresent(c -> add.accept(waypoint(c, s.getOriginCity() + " airport")));
            cities.airport(s.getDestCity()).ifPresent(c -> add.accept(waypoint(c, s.getDestCity() + " airport")));
        }
        cities.hub(s.getDestCity()).ifPresent(c -> add.accept(waypoint(c, "Destination hub")));

        Address o = s.getOriginAddress();
        Address d = s.getDestAddress();
        return new Route(
                o != null ? o.getLatitude() : null, o != null ? o.getLongitude() : null, s.getOriginCity(),
                d != null ? d.getLatitude() : null, d != null ? d.getLongitude() : null, s.getDestCity(),
                waypoints);
    }

    private static Waypoint waypoint(Coord c, String label) {
        return new Waypoint(c.lat(), c.lon(), label);
    }
}
