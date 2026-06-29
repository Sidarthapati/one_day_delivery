package com.oneday.app.demo;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.dispatch.demo.DispatchDemoService;
import com.oneday.dispatch.demo.DispatchDemoService.DaView;
import com.oneday.dispatch.demo.DispatchDemoService.TaskView;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.service.GridService;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.DeliveryOtpService;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.auth.repository.UserRepository;
import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.kafka.EventStreams;
import com.oneday.grid.dto.response.DaTerritoryResponse;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.common.kafka.events.ParcelSortedForDeliveryEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Demo-only ({@code @Profile("!prod")}) DA-app integration surface. Lives in {@code app} — the
 * composition root — because it is the only module that sees both M4 (orders) and M5 (dispatch):
 *
 * <ul>
 *   <li>{@code POST /api/demo/da/assign-pickup?ref=} — assigns a <b>real</b> booked shipment to a DA
 *       via M5 ({@link DispatchService#assignPickup}), transitions M4 {@code BOOKED → PICKUP_ASSIGNED},
 *       mints the pickup OTP, and returns the assigned DA + its van + cron meeting vertex/time. This
 *       is the keystone that connects a customer booking to a DA's real queue.</li>
 *   <li>{@code GET /api/demo/da/{daId}/tasks?cityId=&date=} — that DA's pickups &amp; drops with full
 *       detail (shipment ref, hex, pickup/drop coords, van id, meeting vertex + times), joining M5's
 *       queue with M4 shipment data for the DA app.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/demo/da")
@Profile("!prod")
public class DemoDaController {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStateMachine stateMachine;
    private final PickupOtpService pickupOtpService;
    private final DeliveryOtpService deliveryOtpService;
    private final GridService gridService;
    private final DispatchService dispatchService;
    private final DispatchDemoService dispatchDemoService;
    private final RabbitTemplate rabbitTemplate;
    private final BookingService bookingService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * In-memory demo cache of the cleartext pickup OTP per shipment so repeated "Refresh status" polls
     * show a STABLE code — the OTP service only persists the BCrypt hash and hands back cleartext once.
     */
    private final java.util.Map<UUID, String> otpCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Same idea for the last-mile delivery OTP (the recipient's door code) so it stays stable across polls. */
    private final java.util.Map<UUID, String> deliveryOtpCache = new java.util.concurrent.ConcurrentHashMap<>();

    public DemoDaController(ShipmentRepository shipmentRepository, ShipmentStateMachine stateMachine,
                            PickupOtpService pickupOtpService, DeliveryOtpService deliveryOtpService,
                            GridService gridService, DispatchService dispatchService,
                            DispatchDemoService dispatchDemoService, RabbitTemplate rabbitTemplate,
                            BookingService bookingService, UserRepository userRepository,
                            JdbcTemplate jdbcTemplate) {
        this.shipmentRepository = shipmentRepository;
        this.stateMachine = stateMachine;
        this.pickupOtpService = pickupOtpService;
        this.deliveryOtpService = deliveryOtpService;
        this.gridService = gridService;
        this.dispatchService = dispatchService;
        this.dispatchDemoService = dispatchDemoService;
        this.rabbitTemplate = rabbitTemplate;
        this.bookingService = bookingService;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── DTOs ────────────────────────────────────────────────────────────────────────────────────

    public record AssignResponse(String ref, UUID shipmentId, String outcome, String otp,
                                 UUID daId, String daShort, UUID vanId, String vanShort,
                                 Double vertexLat, Double vertexLon, String meetingTime,
                                 Integer queuePosition, String message) {}

    public record DaTask(UUID shipmentId, String ref, String taskType, UUID tileId,
                         double lat, double lon, String address,
                         boolean cronSafe, boolean crossTerritory, String status,
                         int position, String eta, boolean cod, String m4State) {}

    public record ClearBookingsResponse(String email, int shipments, int otps, int payments,
                                        int history, int dispatchTasks, String message) {}

    /**
     * Demo reset: wipe every shipment booked by {@code email} (default {@code b2c@demo.in}) plus all its
     * child rows across M4 (otps, payments, state history) and M5 (dispatch queue, deferrals, audit), so a
     * demo starts from a clean slate. Scoped strictly to that customer's {@code booked_by_user_id} — never
     * touches anyone else's data. {@code @Profile("!prod")}, so it can't run in production. After clearing,
     * press <b>Reset</b> on the Execution tab to drop the in-memory M5 roster/queues too.
     */
    @Transactional
    @PostMapping("/bookings/clear")
    public ClearBookingsResponse clearBookings(
            @RequestParam(defaultValue = "b2c@demo.in") String email) {
        UUID userId = userRepository.findByEmail(email).map(u -> u.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user: " + email));
        String inUserShipments = " WHERE shipment_id IN (SELECT id FROM shipments WHERE booked_by_user_id = ?)";
        // Child rows first (only delivery_otps has a hard FK; the rest reference shipment_id by UUID).
        int otps = del("DELETE FROM delivery_otps" + inUserShipments, userId)
                 + del("DELETE FROM pickup_otps" + inUserShipments, userId);
        int payments = del("DELETE FROM payment_transactions" + inUserShipments, userId);
        int history  = del("DELETE FROM shipment_state_history" + inUserShipments, userId);
        int tasks = del("DELETE FROM dispatch_queue" + inUserShipments, userId)
                  + del("DELETE FROM deferred_dispatch" + inUserShipments, userId)
                  + del("DELETE FROM da_assignment_audit" + inUserShipments, userId);
        int shipments = del("DELETE FROM shipments WHERE booked_by_user_id = ?", userId);
        otpCache.clear();
        deliveryOtpCache.clear();
        return new ClearBookingsResponse(email, shipments, otps, payments, history, tasks,
                shipments + " shipment(s) cleared for " + email + " — press Reset to clear the M5 roster too.");
    }

    /** Run one scoped DELETE; tolerate a table that doesn't exist on a leaner local DB (returns 0). */
    private int del(String sql, UUID userId) {
        try {
            return jdbcTemplate.update(sql, userId);
        } catch (BadSqlGrammarException missingTable) {
            return 0;
        }
    }

    public record DaTasksResponse(UUID daId, String daShort, UUID vanId, String vanShort,
                                  Double vertexLat, Double vertexLon, String meetingTime,
                                  List<String> meetingTimes, Double distanceToCronKm,
                                  List<DaTask> pickups, List<DaTask> deliveries) {}

    public record StatusResponse(String ref, UUID shipmentId, String state, String stage, String stageLabel,
                                 boolean assigned, UUID daId, String daShort, UUID vanId, String vanShort,
                                 Double vertexLat, Double vertexLon, String meetingTime,
                                 String otp, String leg, String message) {}

    // ── assign a real booking to a DA (keystone) ──────────────────────────────────────────────────

    @Transactional
    @PostMapping("/assign-pickup")
    public AssignResponse assignPickup(@RequestParam String ref) {
        Shipment shipment = shipmentRepository.findByShipmentRef(ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found: " + ref));

        if (shipment.getState() != ShipmentState.BOOKED && shipment.getState() != ShipmentState.PICKUP_ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Assign-pickup is only valid from BOOKED (current: " + shipment.getState() + ")");
        }

        Double lat = shipment.getOriginAddress() != null ? shipment.getOriginAddress().getLatitude() : null;
        Double lon = shipment.getOriginAddress() != null ? shipment.getOriginAddress().getLongitude() : null;
        if (lat == null || lon == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Shipment has no pickup coordinates");
        }
        ServiceableAtResponse loc = gridService.serviceableAt(lat, lon);
        if (loc == null || loc.cityId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Pickup point is outside every serviceable grid");
        }
        UUID cityId = loc.cityId();
        UUID tileId = shipment.getOriginTileId() != null ? shipment.getOriginTileId() : loc.hexId();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

        // If M5 already has this shipment queued (the event pipeline assigned it), reuse that DA;
        // otherwise assign now. Either way we end up with a real DA on a real queue.
        UUID daId = findAssignedDa(cityId, today, shipment.getId());
        String outcome;
        Integer queuePos = null;
        if (daId == null) {
            String paymentMode = shipment.getPaymentMode() != null ? shipment.getPaymentMode().name() : null;
            AssignmentResult r = dispatchService.assignPickup(shipment.getId(), cityId, lat, lon, tileId, paymentMode);
            outcome = r.outcome().name();
            if (r.outcome() == com.oneday.dispatch.service.AssignmentOutcome.DEFERRED) {
                return new AssignResponse(ref, shipment.getId(), outcome, null, null, null, null, null,
                        null, null, null, null,
                        "No DA available (" + (r.deferReason() != null ? r.deferReason().name() : "DEFERRED")
                                + ") — load a shift for this city in the Dispatch tab first, then assign again.");
            }
            daId = r.daId();
            queuePos = r.queuePosition();
        } else {
            outcome = "ALREADY_ASSIGNED";
        }

        // Transition M4 + mint the OTP (the customer's pickup code).
        boolean wasBooked = shipment.getState() == ShipmentState.BOOKED;
        if (wasBooked) {
            stateMachine.transition(shipment.getId(), ShipmentState.PICKUP_ASSIGNED,
                    TransitionContext.fromApi("demo-assign", ref));
        }
        String otp = wasBooked ? pickupOtpService.generate(shipment.getId())
                               : pickupOtpService.resend(shipment.getId());

        // Read back the DA's van + cron meeting vertex/time for the customer + DA app.
        DaView da = findDaView(cityId, today, daId);
        return new AssignResponse(ref, shipment.getId(), outcome, otp,
                daId, shortId(daId),
                da != null ? da.vanId() : null, da != null ? shortId(da.vanId()) : null,
                da != null ? da.cronVertexLat() : null, da != null ? da.cronVertexLon() : null,
                da != null ? da.cronMeetingTime() : null,
                queuePos, null);
    }

    // ── customer-facing pickup status (read-oriented; the customer does NOT assign) ────────────────

    /**
     * What the customer sees after booking. Assignment is <b>M5's</b> job — {@code ShipmentEventsConsumer}
     * reacts to the {@code CREATED} event and places the pickup on a DA automatically; the customer never
     * chooses anyone. This endpoint only <em>reveals</em> that decision. On the first poll where M5 has
     * assigned but M4 hasn't caught up, it completes the M4 side (BOOKED → PICKUP_ASSIGNED + mint OTP) —
     * the demo stand-in for the M5 → M4 {@code PICKUP_ASSIGNED} event the lifecycle will emit for real.
     * The cleartext OTP is cached so the code stays stable across refreshes; safe to poll.
     */
    @Transactional
    @PostMapping("/refresh-status")
    public StatusResponse refreshStatus(@RequestParam String ref) {
        Shipment shipment = shipmentRepository.findByShipmentRef(ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found: " + ref));
        ShipmentState state = shipment.getState();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

        // Past pickup already → just report the journey stage (no DA reveal needed). When the parcel is
        // out for delivery (DROP_COLLECTED), reveal the recipient's delivery OTP — the door code the DA
        // must enter to complete the drop.
        if (state != ShipmentState.BOOKED && state != ShipmentState.PICKUP_ASSIGNED) {
            Stage s = stageFor(state);
            String otp = state == ShipmentState.DROP_COLLECTED
                    ? deliveryOtpCache.get(shipment.getId())
                    : otpCache.get(shipment.getId());
            String msg = (state == ShipmentState.DROP_COLLECTED && otp != null)
                    ? "Out for delivery — give the agent your delivery code " + otp + " to receive your parcel."
                    : s.label();
            return new StatusResponse(ref, shipment.getId(), state.name(), s.code(), s.label(),
                    true, null, null, null, null, null, null, null, otp, s.leg(), msg);
        }

        // Targeted 2-query lookup — the DA carrying this shipment's pickup, NOT the whole-city state()
        // (which reads every DA from the remote DB and made this poll take 6–17s).
        DaView da = dispatchDemoService.daForPickup(shipment.getId()).orElse(null);
        UUID daId = da != null ? da.daId() : null;

        if (daId == null) {
            return new StatusResponse(ref, shipment.getId(), state.name(), "FINDING_DA",
                    "Finding a delivery associate…", false, null, null, null, null, null, null, null,
                    null, "first-mile pickup",
                    "We're matching your pickup to a nearby delivery associate. Refresh in a moment.");
        }

        // M5 has assigned. Complete the M4 side once, then reveal the DA + a stable OTP.
        if (state == ShipmentState.BOOKED) {
            stateMachine.transition(shipment.getId(), ShipmentState.PICKUP_ASSIGNED,
                    TransitionContext.fromApi("demo-status", ref));
            otpCache.put(shipment.getId(), pickupOtpService.generate(shipment.getId()));
        }
        String otp = otpCache.computeIfAbsent(shipment.getId(), id -> {
            try { return pickupOtpService.resend(id); } catch (RuntimeException e) { return null; }
        });

        return new StatusResponse(ref, shipment.getId(), "PICKUP_ASSIGNED", "DA_ASSIGNED",
                "Delivery associate assigned", true, daId, shortId(daId),
                da != null ? da.vanId() : null, da != null ? shortId(da.vanId()) : null,
                da != null ? da.cronVertexLat() : null, da != null ? da.cronVertexLon() : null,
                da != null ? da.cronMeetingTime() : null, otp, "first-mile pickup",
                "A delivery associate is on the way to pick up your parcel.");
    }

    private record Stage(String code, String label, String leg) {}

    private Stage stageFor(ShipmentState s) {
        return switch (s) {
            case PICKED_UP            -> new Stage("PICKED_UP", "Picked up — heading to the hub", "first-mile pickup");
            case HANDED_TO_PICKUP_VAN -> new Stage("AT_VAN", "Handed to the van — en route to the hub", "first-mile pickup");
            case DROP_ASSIGNED        -> new Stage("OUT_FOR_DELIVERY", "Out for delivery", "last-mile drop");
            case DROP_COLLECTED       -> new Stage("OUT_FOR_DELIVERY", "Delivery associate has your parcel", "last-mile drop");
            case DROPPED              -> new Stage("DELIVERED", "Delivered", "last-mile drop");
            default                   -> new Stage("IN_TRANSIT", "In transit", "—");
        };
    }

    // ── auto-verify: simulate every DA doing the door OTP handshake (bulk shortcut) ─────────────────

    public record AutoVerifyResponse(int verified, int alreadyPickedUp, int skippedSynthetic, String message) {}

    /**
     * Demo shortcut: simulate each DA completing the door OTP handshake for its assigned pickups, so the
     * van run carries real, OTP-collected parcels. For every QUEUED PICKUP backed by a real shipment:
     * BOOKED → PICKUP_ASSIGNED → PICKED_UP (the handshake's result) and the M5 task → IN_PROGRESS
     * (ready-for-van). Synthetic load-test parcels (no booking) are skipped — a parcel with no customer
     * can't be OTP-verified, so the van won't carry it. Not {@code @Transactional} at the method level so
     * one bad task doesn't roll back the rest.
     */
    @PostMapping("/auto-verify")
    public AutoVerifyResponse autoVerify(@RequestParam UUID cityId,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int verified = 0, already = 0, synthetic = 0;
        for (DaView da : dispatchDemoService.state(cityId, date).das()) {
            for (TaskView t : da.queue()) {
                if (!"PICKUP".equals(t.taskType())) continue;
                Optional<Shipment> opt = shipmentRepository.findById(t.shipmentId());
                if (opt.isEmpty()) { synthetic++; continue; }     // synthetic load-test parcel, no booking
                try {
                    ShipmentState st = opt.get().getState();
                    String ref = opt.get().getShipmentRef();
                    if (st == ShipmentState.BOOKED) {
                        stateMachine.transition(t.shipmentId(), ShipmentState.PICKUP_ASSIGNED,
                                TransitionContext.fromApi("demo-autoverify", ref));
                        st = ShipmentState.PICKUP_ASSIGNED;
                    }
                    if (st == ShipmentState.PICKUP_ASSIGNED) {
                        stateMachine.transition(t.shipmentId(), ShipmentState.PICKED_UP,
                                TransitionContext.fromApi("demo-autoverify", ref));
                        dispatchDemoService.markPickedUp(t.shipmentId());
                        verified++;
                    } else if (st == ShipmentState.PICKED_UP) {
                        dispatchDemoService.markPickedUp(t.shipmentId());   // ensure M5 task is IN_PROGRESS
                        already++;
                    }
                } catch (RuntimeException ignore) { /* skip a task that can't transition right now */ }
            }
        }
        return new AutoVerifyResponse(verified, already, synthetic,
                verified + " pickup(s) collected (OTP handshake simulated)"
                        + (already > 0 ? ", " + already + " already collected" : "")
                        + (synthetic > 0 ? ", " + synthetic + " synthetic skipped" : ""));
    }

    public record FirstMileResponse(int advanced, int skipped, String message) {}

    /**
     * Demo: close the first mile — for every {@code PICKED_UP} shipment whose origin is {@code city},
     * advance {@code PICKED_UP → HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB} (the van carried it back to the
     * origin hub) and complete its M5 pickup task. Run this after "Run the day". Stands in for M8's
     * {@code HUB_ORIGIN_IN} scan / M7 origin-hub receiving, which aren't built — that's the real seam.
     */
    @PostMapping("/pickups/to-hub")
    public FirstMileResponse pickupsToHub(@RequestParam String city,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int advanced = 0, skipped = 0;
        for (Shipment sh : shipmentRepository.findByState(ShipmentState.PICKED_UP)) {
            if (sh.getOriginCity() == null || !sh.getOriginCity().equalsIgnoreCase(city)) continue;
            try {
                String ref = sh.getShipmentRef();
                stateMachine.transition(sh.getId(), ShipmentState.HANDED_TO_PICKUP_VAN,
                        TransitionContext.fromApi("demo-firstmile-hub", ref));
                stateMachine.transition(sh.getId(), ShipmentState.AT_ORIGIN_HUB,
                        TransitionContext.fromApi("demo-firstmile-hub", ref));
                dispatchDemoService.markPickupCompleted(sh.getId());
                advanced++;
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        return new FirstMileResponse(advanced, skipped,
                advanced + " pickup(s) reached the origin hub (→ AT_ORIGIN_HUB)"
                        + (skipped > 0 ? ", " + skipped + " skipped" : ""));
    }

    // ── spread seed: book real shipments across MANY DA territories (one per DA), not one hex ────────

    public record SeedSpreadResponse(int booked, int skipped, String kind, List<String> refs, String message) {}

    private static final double[] OTHER_CITY_PT = {19.0760, 72.8777};   // Mumbai centroid — the non-spread leg

    /**
     * Demo: book {@code count} real COD B2C shipments whose spread end lands in a <em>different DA
     * territory</em> each (hex centroid per DA), so the demo involves many DAs instead of one hex.
     * <ul>
     *   <li>{@code kind=PICKUP} — origin spread across {@code city}'s DAs (dest = a fixed other city) →
     *       M5 auto-assigns each pickup to a different DA.</li>
     *   <li>{@code kind=DROP} — dest spread across {@code city}'s DAs (origin = a fixed other city) →
     *       then {@code /drops/dispatch} fast-forwards them to out-for-delivery on different loops.</li>
     * </ul>
     * Requires today's plan/territories to exist (run Prepare first). Booked under the demo customer so
     * they appear in Your Bookings.
     */
    @PostMapping("/seed/spread")
    public SeedSpreadResponse seedSpread(@RequestParam UUID cityId,
                                         @RequestParam String city,
                                         @RequestParam(defaultValue = "PICKUP") String kind,
                                         @RequestParam(defaultValue = "12") int count,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        boolean pickup = "PICKUP".equalsIgnoreCase(kind);
        UUID userId = userRepository.findByEmail("b2c@demo.in").map(u -> u.getId()).orElse(UUID.randomUUID());
        List<double[]> spread = territoryCentroids(cityId, date, count);
        List<String> refs = new ArrayList<>();
        int skipped = 0;
        for (double[] pt : spread) {
            try {
                BookingRequest req = pickup
                        ? booking(pt, city, "110001", OTHER_CITY_PT, "Mumbai", "400001")
                        : booking(OTHER_CITY_PT, "Mumbai", "400001", pt, city, "110001");
                if (!pickup) {
                    // The drop demo only exercises the LAST mile. Model the origin (first-mile) as
                    // SELF_DROP so M5 doesn't try to assign a pickup in the un-staffed origin city —
                    // which otherwise piles up as phantom origin-city deferrals. The sender "drops at
                    // the origin hub"; dispatchDrops then fast-forwards the flight + last-mile.
                    req.setPickupType(PickupType.SELF_DROP);
                }
                BookingResponse r = bookingService.book(req, UUID.randomUUID().toString(),
                        userId.toString(), CustomerType.B2C);
                refs.add(r.getShipmentRef());
                if (!pickup) {
                    // Last-mile assignment is normally event-driven off M4 (blocked — Q-M4-2). Trigger M5's
                    // real delivery engine here so the drop lands on the DA owning the dest hex and shows on
                    // the DA hover/app immediately — symmetric with how a spread pickup appears.
                    shipmentRepository.findByShipmentRef(r.getShipmentRef()).ifPresent(sh -> {
                        if (sh.getDestTileId() != null) {
                            dispatchService.assignDelivery(sh.getId(), cityId, pt[0], pt[1], sh.getDestTileId());
                        }
                    });
                }
            } catch (RuntimeException e) {
                skipped++;   // a centroid outside the grid or an unpriced route is skipped
            }
        }
        String msg = refs.size() + " spread " + (pickup ? "pickup" : "drop")
                + "(s) booked across " + refs.size() + " DA territories"
                + (skipped > 0 ? ", " + skipped + " skipped" : "")
                + (pickup ? " — M5 auto-assigns each to its DA"
                          : " — assigned to DAs (hover to see); now press 'Dispatch drops' to send out for delivery");
        return new SeedSpreadResponse(refs.size(), skipped, pickup ? "PICKUP" : "DROP", refs, msg);
    }

    /** One representative coordinate per DA territory (hex centroid = average of its corner vertices). */
    private List<double[]> territoryCentroids(UUID cityId, LocalDate date, int count) {
        List<double[]> pts = new ArrayList<>();
        for (DaTerritoryResponse t : gridService.getDaTerritories(cityId, date)) {
            if (pts.size() >= count) break;
            if (t.hexes().isEmpty() || t.hexes().get(0).vertices().isEmpty()) continue;
            List<GridVertexResponse> vs = t.hexes().get(0).vertices();
            double lat = vs.stream().mapToDouble(GridVertexResponse::lat).average().orElse(0);
            double lon = vs.stream().mapToDouble(GridVertexResponse::lon).average().orElse(0);
            pts.add(new double[]{lat, lon});
        }
        return pts;
    }

    /** Build a COD B2C booking between an origin point/city and a dest point/city (lat/lon drive serviceability). */
    private BookingRequest booking(double[] origin, String originCity, String originPin,
                                   double[] dest, String destCity, String destPin) {
        BookingRequest r = new BookingRequest();
        r.setSenderName("Demo Sender");
        r.setSenderPhone("+919000000001");
        r.setOriginAddress(address(origin, originCity, originPin));
        r.setOriginCity(originCity);
        r.setOriginPincode(originPin);
        r.setReceiverName("Demo Receiver");
        r.setReceiverPhone("+919000000002");
        r.setDestAddress(address(dest, destCity, destPin));
        r.setDestCity(destCity);
        r.setDestPincode(destPin);
        r.setWeightGrams(1000);
        r.setLengthCm((short) 20);
        r.setWidthCm((short) 15);
        r.setHeightCm((short) 10);
        r.setDeclaredValuePaise(50_000L);
        r.setPickupType(PickupType.DA_PICKUP);
        r.setDropType(DropType.DA_DELIVERY);
        r.setPaymentMode(PaymentMode.COD);
        return r;
    }

    private Address address(double[] pt, String city, String pin) {
        Address a = new Address();
        a.setLine1("Demo " + city + " address");
        a.setCity(city);
        a.setPincode(pin);
        a.setState(city);
        a.setLatitude(pt[0]);
        a.setLongitude(pt[1]);
        return a;
    }

    // ── last-mile drops: dispatch real shipments to delivery + simulate the recipient OTP ───────────

    public record DropDispatchResponse(int dispatched, int skipped, List<String> refs, String message) {}
    public record DeliveryVerifyResponse(int delivered, int skipped, String message) {}

    // The M4 delivery-side state path, by delivery type. We fast-forward each booked drop shipment along
    // this path to DROP_COLLECTED (out for delivery), standing in for the M7 hub + M9 flight legs that the
    // demo doesn't run. The van's hub→DA leg is then shown by the M6 sim; the recipient OTP completes it.
    /** The M4 states from BOOKED to DROP_COLLECTED for a shipment, branching on its first-mile (DA_PICKUP
     *  vs SELF_DROP) and delivery type (INTERCITY flies; SAME_CITY skips the air legs). Spread-drops are
     *  SELF_DROP (no origin pickup); the drop20 bulk is DA_PICKUP. */
    private static List<ShipmentState> dropPath(Shipment sh) {
        List<ShipmentState> p = new ArrayList<>();
        // first-mile → AT_ORIGIN_HUB
        if (sh.getPickupType() == PickupType.SELF_DROP) {
            p.add(ShipmentState.AWAITING_SELF_DROP);
            p.add(ShipmentState.AT_ORIGIN_HUB);
        } else {
            p.add(ShipmentState.PICKUP_ASSIGNED);
            p.add(ShipmentState.PICKED_UP);
            p.add(ShipmentState.HANDED_TO_PICKUP_VAN);
            p.add(ShipmentState.AT_ORIGIN_HUB);
        }
        // origin hub → dest hub (intercity flies; same-city short-circuits)
        p.add(ShipmentState.ORIGIN_HUB_PROCESSING);
        p.add(ShipmentState.IN_TAKEOFF_BAG);
        if (sh.getDeliveryType() == DeliveryType.SAME_CITY) {
            p.add(ShipmentState.HANDED_TO_DROP_VAN);
        } else {
            p.add(ShipmentState.DISPATCHED_TO_AIRPORT);
            p.add(ShipmentState.AT_AIRPORT);
            p.add(ShipmentState.DEPARTED);
            p.add(ShipmentState.LANDED);
            p.add(ShipmentState.DISPATCHED_TO_HUB);
            p.add(ShipmentState.AT_DEST_HUB);
            p.add(ShipmentState.DEST_HUB_PROCESSING);
            p.add(ShipmentState.HANDED_TO_DROP_VAN);
        }
        // last-mile → out for delivery
        p.add(ShipmentState.DROP_ASSIGNED);
        p.add(ShipmentState.DROP_COLLECTED);
        return p;
    }

    /**
     * Demo: take every BOOKED {@code DA_DELIVERY} shipment destined for {@code city}, fast-forward it to
     * {@code DROP_COLLECTED} (out for delivery), mint its delivery OTP, and publish a real
     * {@code ParcelSortedForDelivery} so M6 binds it to a drop-van loop. After this, "Run the day" carries
     * these real parcels hub→van→DA, and the recipient OTP (or "Auto-verify deliveries") completes them.
     */
    @PostMapping("/drops/dispatch")
    public DropDispatchResponse dispatchDrops(@RequestParam UUID cityId,
                                              @RequestParam String city,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<String> refs = new ArrayList<>();
        int skipped = 0;
        for (Shipment sh : shipmentRepository.findByState(ShipmentState.BOOKED)) {
            if (sh.getDropType() != DropType.DA_DELIVERY) continue;
            if (sh.getDestCity() == null || !sh.getDestCity().equalsIgnoreCase(city)) continue;
            if (sh.getDestTileId() == null) { skipped++; continue; }
            try {
                walkToDropCollected(sh);
                dispatchDemoService.markDelivering(sh.getId());   // M5 DELIVERY task QUEUED → IN_PROGRESS
                String otp = deliveryOtpService.generate(sh.getId());
                deliveryOtpCache.put(sh.getId(), otp);
                publishSortedForDelivery(sh.getId(), cityId, sh.getDestTileId(), date);
                refs.add(sh.getShipmentRef());
            } catch (RuntimeException e) {
                skipped++;   // a shipment that can't walk right now is left for the next call
            }
        }
        String msg = refs.size() + " drop(s) dispatched to last-mile (out for delivery, OTP minted)"
                + (skipped > 0 ? ", " + skipped + " skipped" : "");
        return new DropDispatchResponse(refs.size(), skipped, refs, msg);
    }

    /**
     * Demo shortcut: simulate every recipient completing the door OTP handshake — for each
     * {@code DROP_COLLECTED} shipment destined for {@code city}, transition {@code → DROPPED}. The genuine
     * per-parcel path is {@code POST /internal/v1/shipments/{ref}/delivery-otp/verify} with the real code.
     */
    @PostMapping("/drops/auto-verify")
    public DeliveryVerifyResponse autoVerifyDeliveries(@RequestParam String city,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int delivered = 0, skipped = 0;
        for (Shipment sh : shipmentRepository.findByState(ShipmentState.DROP_COLLECTED)) {
            if (sh.getDestCity() == null || !sh.getDestCity().equalsIgnoreCase(city)) continue;
            try {
                stateMachine.transition(sh.getId(), ShipmentState.DROPPED,
                        TransitionContext.fromApi("demo-delivery-autoverify", sh.getShipmentRef()));
                delivered++;
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        return new DeliveryVerifyResponse(delivered, skipped,
                delivered + " parcel(s) delivered (recipient OTP simulated)"
                        + (skipped > 0 ? ", " + skipped + " skipped" : ""));
    }

    /** Walk a BOOKED shipment along its first-mile + delivery-type path to DROP_COLLECTED, one step at a time. */
    private void walkToDropCollected(Shipment sh) {
        String ref = sh.getShipmentRef();
        for (ShipmentState target : dropPath(sh)) {
            stateMachine.transition(sh.getId(), target,
                    TransitionContext.fromApi("demo-drop-dispatch", ref));
        }
    }

    /** Publish the M7-shaped sorted-for-delivery event (parcelId = shipmentId) so M6 binds the drop. */
    private void publishSortedForDelivery(UUID shipmentId, UUID cityId, UUID destTileId, LocalDate date) {
        Instant now = Instant.now();
        rabbitTemplate.convertAndSend(EventStreams.HUB_EVENTS, "ParcelSortedForDelivery",
                new ParcelSortedForDeliveryEvent(shipmentId, cityId, destTileId, date, now,
                        now.plus(Duration.ofHours(6))));
    }

    // ── a DA's enriched task list (DA app) ────────────────────────────────────────────────────────

    @GetMapping("/{daId}/tasks")
    public DaTasksResponse tasks(@PathVariable UUID daId, @RequestParam UUID cityId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        DaView da = findDaView(cityId, date, daId);
        if (da == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DA not loaded for this city/date: " + daId);
        }
        List<DaTask> pickups = new ArrayList<>();
        List<DaTask> deliveries = new ArrayList<>();
        for (TaskView t : da.queue()) {
            Shipment sh = shipmentRepository.findById(t.shipmentId()).orElse(null);
            // A cancelled booking is no longer a real pickup/drop — don't show it on the DA's run. M5 can't
            // always drop an IN_PROGRESS task on cancel ("left for ops/M11"), so filter here where we see M4 state.
            if (sh != null && sh.getState() == ShipmentState.CANCELLED) continue;
            DaTask task = enrich(t, sh);
            if ("DELIVERY".equals(t.taskType())) {
                deliveries.add(task);
            } else {
                pickups.add(task);
            }
        }
        return new DaTasksResponse(daId, shortId(daId), da.vanId(), shortId(da.vanId()),
                da.cronVertexLat(), da.cronVertexLon(), da.cronMeetingTime(),
                da.meetingTimes(), da.distanceToCronKm(), pickups, deliveries);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** Join the M5 task with M4 shipment data (ref + human address); synthetic tasks have no M4 row (sh null). */
    private DaTask enrich(TaskView t, Shipment sh) {
        String ref = null;
        String address = null;
        boolean cod = false;
        if (sh != null) {
            ref = sh.getShipmentRef();
            cod = sh.getPaymentMode() != null && "COD".equalsIgnoreCase(sh.getPaymentMode().name());
            // pickup → origin address; delivery → destination address (where the DA drops it).
            var addr = "DELIVERY".equals(t.taskType()) ? sh.getDestAddress() : sh.getOriginAddress();
            if (addr != null) {
                address = (addr.getLine1() != null ? addr.getLine1() : "")
                        + (addr.getCity() != null ? ", " + addr.getCity() : "");
            }
        }
        return new DaTask(t.shipmentId(), ref, t.taskType(), t.tileId(), t.taskLat(), t.taskLon(),
                address, t.cronSafe(), t.crossTerritory(), t.status(), t.position(),
                t.expectedEta() != null ? t.expectedEta().toString() : null, cod,
                sh != null ? sh.getState().name() : null);
    }

    private UUID findAssignedDa(UUID cityId, LocalDate date, UUID shipmentId) {
        for (DaView da : dispatchDemoService.state(cityId, date).das()) {
            for (TaskView t : da.queue()) {
                if (shipmentId.equals(t.shipmentId())) {
                    return da.daId();
                }
            }
        }
        return null;
    }

    private DaView findDaView(UUID cityId, LocalDate date, UUID daId) {
        return dispatchDemoService.state(cityId, date).das().stream()
                .filter(d -> d.daId().equals(daId)).findFirst().orElse(null);
    }

    private static String shortId(UUID id) {
        return id == null ? null : id.toString().substring(0, 8);
    }
}
