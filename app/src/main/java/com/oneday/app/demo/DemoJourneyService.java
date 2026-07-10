package com.oneday.app.demo;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.batch.ShiftLoadJob;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.OtpVerificationService;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.service.GridService;
import com.oneday.orders.domain.Address;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.ShipmentInfo;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentLookupService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import com.oneday.routing.demo.DemoVanDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demo-only cross-module "journey" driver (Phase 2 of the M7 execution demo). Unlike the M6 execution
 * demo — which faked the two feeds — this walks a parcel through the <b>real</b> M4→M5→M6→M7→M6 pipeline
 * over the real broker, so a live audience sees the whole first-mile + last-mile journey:
 *
 * <ol>
 *   <li><b>Book</b> N real M4 shipments ({@link BookingService}) from live-grid coordinates → each emits
 *       {@code ShipmentCreatedEvent}.</li>
 *   <li><b>Assign</b> — M5 consumes and assigns a pickup DA (real, async over the bus); the driver polls
 *       {@link DispatchQueueRepository} to read each parcel's PICKUP task + DA back.</li>
 *   <li><b>DA pickup</b> — en-route → OTP → van-handoff (→ {@code PICKUP_COMPLETED}, Seam A).</li>
 *   <li><b>Collect van</b> — M6 binds COLLECT; telemetry/custody bring parcels to the origin hub.</li>
 *   <li><b>Origin hub</b> — M7 sort → flight bag → seal → dispatch; compressed clock.</li>
 *   <li><b>Freight</b> — compressed flight time → {@code AT_DEST_HUB}.</li>
 *   <li><b>Dest hub</b> — M7 inbound sort → {@code PARCEL_SORTED_FOR_DELIVERY} (Seam B).</li>
 *   <li><b>Deliver van</b> — M6 binds DELIVER; DA drop → {@code DROP_COMPLETED}. Done.</li>
 * </ol>
 *
 * <p>Single run at a time on a background thread; a per-parcel {@link JourneyRecord} advances stage by
 * stage for the UI, plus the {@link DemoLog} raw feed. All coordinates are drawn from the live grid so
 * every serviceability/bind resolves. {@code @Profile("!prod")} — never active in production.</p>
 *
 * <p><b>Status:</b> stages 1–2 (book + assignment) are live; stages 3–8 are being wired incrementally
 * and validated against the real broker — see {@code docs/M7/M7-EXECUTION-DEMO-CARRYOVER.md}.</p>
 */
@Service
@Profile("!prod")
public class DemoJourneyService {

    private static final Logger log = LoggerFactory.getLogger(DemoJourneyService.class);

    /** Fixed synthetic customer for demo bookings (permitAll under !prod; no real user needed). */
    private static final String DEMO_USER_ID = "00000000-0000-0000-0000-0000000000d0";

    private final BookingService bookingService;
    private final ShipmentLookupService shipmentLookupService;
    private final PickupOtpService pickupOtpService;
    private final DispatchQueueRepository dispatchQueueRepository;
    private final DaCronAssignmentRepository cronRepository;
    private final DaStatusService daStatusService;
    private final DaTaskService daTaskService;
    private final OtpVerificationService otpVerificationService;
    private final ShipmentStateMachine stateMachine;
    private final DemoVanDriver vanDriver;
    private final GridService gridService;
    private final ShiftLoadJob shiftLoadJob;
    private final Clock clock;

    /** The happy-path M4 lifecycle for an INTERCITY / DA_DELIVERY parcel — the driver walks it in order. */
    private static final List<ShipmentState> HAPPY_PATH = List.of(
            ShipmentState.BOOKED, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP,
            ShipmentState.HANDED_TO_PICKUP_VAN, ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING,
            ShipmentState.IN_TAKEOFF_BAG, ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT,
            ShipmentState.DEPARTED, ShipmentState.LANDED, ShipmentState.DISPATCHED_TO_HUB,
            ShipmentState.AT_DEST_HUB, ShipmentState.DEST_HUB_PROCESSING, ShipmentState.HANDED_TO_DROP_VAN,
            ShipmentState.DROP_ASSIGNED, ShipmentState.DROP_COLLECTED, ShipmentState.DROPPED);

    private final DemoLog feed = new DemoLog();
    private final Map<String, JourneyRecord> journeys = new ConcurrentHashMap<>();
    // ref → the DA's active PICKUP task id, captured when M5 assigns (Stage 2), used to drive the DA app.
    private final Map<String, UUID> refToTaskId = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-journey");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancel = false;
    private volatile RunStatus status = RunStatus.idle();

    DemoJourneyService(BookingService bookingService, ShipmentLookupService shipmentLookupService,
                       PickupOtpService pickupOtpService, DispatchQueueRepository dispatchQueueRepository,
                       DaCronAssignmentRepository cronRepository, DaStatusService daStatusService,
                       DaTaskService daTaskService, OtpVerificationService otpVerificationService,
                       ShipmentStateMachine stateMachine, DemoVanDriver vanDriver,
                       GridService gridService, ShiftLoadJob shiftLoadJob, Clock clock) {
        this.bookingService = bookingService;
        this.shipmentLookupService = shipmentLookupService;
        this.pickupOtpService = pickupOtpService;
        this.dispatchQueueRepository = dispatchQueueRepository;
        this.cronRepository = cronRepository;
        this.daStatusService = daStatusService;
        this.daTaskService = daTaskService;
        this.otpVerificationService = otpVerificationService;
        this.stateMachine = stateMachine;
        this.vanDriver = vanDriver;
        this.gridService = gridService;
        this.shiftLoadJob = shiftLoadJob;
        this.clock = clock;
    }

    // ── public surface (controller) ──────────────────────────────────────────────────────────────

    /** Run-level rollup for the status panel. */
    public record RunStatus(String phase, String originCity, String destCity, LocalDate date,
                            int booked, int assigned, int delivered, long lastSeq, String error) {
        static RunStatus idle() {
            return new RunStatus("IDLE", null, null, null, 0, 0, 0, 0, null);
        }
    }

    /** One parcel's live position on the §0 pipeline strip. {@code stage} is the UI token's column. */
    public record JourneyRecord(String shipmentRef, UUID shipmentId, String stage,
                                String originCity, String destCity, UUID daId, UUID vanId,
                                String flightNo, String standNo) {
        JourneyRecord withStage(String s) {
            return new JourneyRecord(shipmentRef, shipmentId, s, originCity, destCity, daId, vanId, flightNo, standNo);
        }
        JourneyRecord withAssignment(UUID da) {
            return new JourneyRecord(shipmentRef, shipmentId, "DA_ASSIGNED", originCity, destCity, da, vanId, flightNo, standNo);
        }
    }

    public synchronized RunStatus start(String originCity, String destCity, int count, int speed) {
        if (running.get()) {
            throw new IllegalStateException("A demo journey is already in progress — stop it first.");
        }
        if (originCity == null || destCity == null || originCity.equalsIgnoreCase(destCity)) {
            throw new IllegalStateException("Provide two distinct serviceable cities for the demo city-pair.");
        }
        // Resolve now so a bad city fails fast (before spawning the run thread).
        UUID originCityId = gridService.resolveCityId(originCity);
        UUID destCityId = gridService.resolveCityId(destCity);
        LocalDate day = LocalDate.now(clock);

        cancel = false;
        feed.clear();
        journeys.clear();
        refToTaskId.clear();
        running.set(true);
        status = new RunStatus("STARTING", originCity, destCity, day, 0, 0, 0, feed.lastSeq(), null);
        runner.submit(() -> runDay(originCity, originCityId, destCity, destCityId, day, Math.max(1, count), speed));
        return status;
    }

    public void stop() {
        cancel = true;
        feed.add("INFO", "Stop requested — finishing current stage.");
    }

    public RunStatus status() {
        return status;
    }

    public List<DemoLog.Entry> events(long after) {
        return feed.since(after);
    }

    public List<JourneyRecord> journeys() {
        return new ArrayList<>(journeys.values());
    }

    // ── the run ──────────────────────────────────────────────────────────────────────────────────

    private void runDay(String originCity, UUID originCityId, String destCity, UUID destCityId,
                        LocalDate day, int count, int speed) {
        try {
            feed.add("INFO", "Journey run — %s → %s, %d parcels, date %s".formatted(originCity, destCity, count, day));

            // Stage 0 — bring the day's DAs on shift (M5's start-of-day bootstrap). Reads each city's
            // APPROVED M3 territory → da_status IDLE + tile registration, so assignPickup has candidate
            // DAs. The @Scheduled ShiftLoadJob runs at 05:45/13:45 IST; a mid-day-approved territory
            // needs this explicit trigger or every pickup defers NO_DA_AVAILABLE.
            try {
                shiftLoadJob.loadShiftsForDate(day);
                feed.add("INFO", "DAs loaded on shift for " + day + " (M5 day bootstrap).");
                // shiftLoadJob registers DAs OFFLINE by design (no ping yet); a DA is assignable only
                // after its first GPS heartbeat (OFFLINE→IDLE). Simulate the shift-start ping for both
                // the pickup city and the delivery city, or M5 defers every task NO_DA_AVAILABLE.
                int online = bringDasOnline(originCityId, day) + bringDasOnline(destCityId, day);
                feed.add("INFO", online + " DAs brought online (GPS heartbeat → IDLE) for pickup + delivery.");
            } catch (RuntimeException e) {
                feed.add("ERROR", "Shift load failed: " + e.getMessage());
            }

            // Stage 1 — book N real M4 shipments (each emits ShipmentCreatedEvent on the bus).
            setPhase("BOOKING", 0, 0, 0);
            List<JourneyRecord> booked = bookShipments(originCity, originCityId, destCity, destCityId, count);
            setPhase("ASSIGNING", booked.size(), 0, 0);

            // Stage 2 — M5 assigns asynchronously; poll the dispatch queue to read each parcel's DA back.
            int assigned = awaitAssignments(booked);
            setPhase("ASSIGNED", booked.size(), assigned, 0);
            feed.add("ASSIGN", "M5 assigned %d/%d parcels to pickup DAs.".formatted(assigned, booked.size()));

            int tickMs = Math.max(40, Math.min(1000, Math.round(200f * 60f / Math.max(1, speed))));

            // Stage 3 — drive the DA app per assigned parcel: en-route → OTP pickup → van-handoff.
            setPhase("PICKUP", booked.size(), assigned, 0);
            int pickedUp = driveDaPickups(booked);
            feed.add("PICKUP", "%d/%d parcels picked up + handed to the collect van.".formatted(pickedUp, assigned));

            // Stage 4 — M6 binds COLLECT on PICKUP_COMPLETED; drive the collect van to the origin hub.
            setPhase("COLLECT_VAN", booked.size(), assigned, 0);
            driveCollectVan(originCityId, day, pickedUp, tickMs);

            // Stage 5–6 — origin hub sort (M7) + compressed freight → destination hub.
            setPhase("HUB_FREIGHT", booked.size(), assigned, 0);
            driveHubAndFreight(booked, day);

            // Stage 7–8 — destination hub sort (M7, Seam B) + deliver van + complete to DROPPED.
            setPhase("DELIVERY", booked.size(), assigned, 0);
            int delivered = driveDelivery(booked, destCityId, day, tickMs);

            setPhase(cancel ? "STOPPED" : "DONE", booked.size(), assigned, delivered);
            feed.add("INFO", cancel ? "Run stopped."
                    : "Day complete — %d/%d parcels delivered end to end.".formatted(delivered, booked.size()));
        } catch (RuntimeException e) {
            log.error("Demo journey failed", e);
            status = new RunStatus("ERROR", originCity, destCity, day, status.booked(), status.assigned(),
                    status.delivered(), feed.lastSeq(), e.getMessage());
            feed.add("ERROR", "Run failed: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Stage 1: book {@code count} B2C shipments over the real pipeline. Returns the created journeys. */
    private List<JourneyRecord> bookShipments(String originCity, UUID originCityId,
                                              String destCity, UUID destCityId, int count) {
        double[] origin = aVertex(originCityId, "origin " + originCity);
        double[] dest = aVertex(destCityId, "dest " + destCity);

        List<JourneyRecord> out = new ArrayList<>();
        for (int i = 0; i < count && !cancel; i++) {
            BookingRequest req = buildRequest(originCity, origin, destCity, dest, i);
            // bookSettled = PREPAID with no gateway call (cart-checkout path) — ideal for a demo.
            BookingResponse resp = bookingService.bookSettled(req, UUID.randomUUID().toString(), DEMO_USER_ID, CustomerType.B2C);
            String ref = resp.getShipmentRef();
            UUID shipmentId = shipmentLookupService.findByRef(ref).map(ShipmentInfo::shipmentId).orElse(null);
            JourneyRecord jr = new JourneyRecord(ref, shipmentId, "BOOKED", originCity, destCity, null, null, null, null);
            journeys.put(ref, jr);
            out.add(jr);
            feed.add("BOOK", "M4 booked %s (%s → %s)".formatted(ref, originCity, destCity));
            bumpBooked();
        }
        return out;
    }

    /** Stage 2: poll the dispatch queue until each parcel has an active PICKUP task (M5 assigned) or timeout. */
    private int awaitAssignments(List<JourneyRecord> booked) {
        int assigned = 0;
        // Assignment is async over the broker; give it a generous, volume-scaled window.
        int maxPolls = 60 + booked.size() * 2;
        List<JourneyRecord> pending = new ArrayList<>(booked);
        for (int i = 0; i < maxPolls && !cancel && !pending.isEmpty(); i++) {
            pending.removeIf(jr -> {
                if (jr.shipmentId() == null) {
                    return true; // can't track without an id; drop
                }
                Optional<DispatchQueue> task = dispatchQueueRepository
                        .findActiveByShipmentIdAndTaskType(jr.shipmentId(), TaskType.PICKUP);
                if (task.isEmpty()) {
                    return false; // keep polling
                }
                UUID daId = task.get().getDaId();
                refToTaskId.put(jr.shipmentRef(), task.get().getId());
                journeys.put(jr.shipmentRef(), jr.withAssignment(daId));
                feed.add("ASSIGN", "M5 ▸ %s assigned to DA %s".formatted(jr.shipmentRef(), shortId(daId)));
                return true;
            });
            assigned = booked.size() - pending.size();
            setPhase("ASSIGNING", booked.size(), assigned, 0);
            settle(500);
        }
        return assigned;
    }

    /** Stage 3: per assigned parcel, drive the DA app end to end — en-route → OTP pickup → van-handoff. */
    private int driveDaPickups(List<JourneyRecord> booked) {
        int done = 0;
        for (JourneyRecord jr0 : booked) {
            if (cancel) break;
            JourneyRecord jr = journeys.get(jr0.shipmentRef());
            UUID taskId = refToTaskId.get(jr.shipmentRef());
            if (jr.daId() == null || taskId == null || jr.shipmentId() == null) {
                continue; // never assigned — nothing to drive
            }
            UUID daId = jr.daId();
            UUID shipmentId = jr.shipmentId();
            String ref = jr.shipmentRef();
            try {
                // M5 emitted PICKUP_ASSIGNED; wait for M4's consumer to apply it (→ PICKUP_ASSIGNED + OTP mint).
                if (!awaitState(ref, ShipmentState.PICKUP_ASSIGNED, 40)) {
                    feed.add("WARN", "%s never reached PICKUP_ASSIGNED — skipping pickup.".formatted(ref));
                    continue;
                }
                daTaskService.markEnRoute(daId, taskId);
                journeys.put(ref, jr.withStage("EN_ROUTE"));

                // M4's own OTP mint is only logged; mint a known cleartext here, then verify → PICKED_UP.
                String otp = pickupOtpService.generate(shipmentId);
                otpVerificationService.verifyOtp(daId, taskId, otp);
                journeys.put(ref, jr.withStage("PICKED_UP"));
                feed.add("PICKUP", "DA %s ▸ picked up %s (OTP ok) → PICKUP_COMPLETED".formatted(shortId(daId), ref));

                // Hand to the cron van (parcel scan = the ref); emits VAN_HANDOFF_COMPLETED → M4 HANDED_TO_PICKUP_VAN.
                daTaskService.recordVanHandoff(daId, taskId, List.of(ref), null);
                journeys.put(ref, jr.withStage("IN_COLLECT_VAN"));
                feed.add("HANDOFF", "DA %s ▸ handed %s to collect van → VAN_HANDOFF_COMPLETED".formatted(shortId(daId), ref));
                done++;
            } catch (RuntimeException e) {
                feed.add("ERROR", "Pickup drive failed for %s: %s".formatted(ref, e.getMessage()));
            }
            settle(150);
        }
        return done;
    }

    /** Poll M4 until the shipment (by ref) reaches {@code target} state, or timeout (~500ms polls). */
    private boolean awaitState(String ref, ShipmentState target, int maxPolls) {
        for (int i = 0; i < maxPolls && !cancel; i++) {
            ShipmentState s = shipmentLookupService.findByRef(ref).map(ShipmentInfo::state).orElse(null);
            if (s == target) {
                return true;
            }
            settle(500);
        }
        return false;
    }

    /** Stage 4: wait for the Seam-A COLLECT binds to settle, then drive the origin-city collect van(s). */
    private void driveCollectVan(UUID originCityId, LocalDate day, int expectedCollect, int tickMs) {
        var plan = vanDriver.approvedPlan(originCityId, day).orElse(null);
        if (plan == null) {
            feed.add("WARN", "No APPROVED plan for the origin city — collect van cannot run (generate+approve a plan).");
            return;
        }
        int bound = vanDriver.waitForBinds(plan.getId(), expectedCollect, () -> cancel);
        feed.add("BIND", "M6 bound %d parcel(s) to collect loop(s) (Seam A).".formatted(bound));
        DemoVanDriver.DriveResult r = vanDriver.driveLoops(originCityId, day, tickMs, feed::add, () -> cancel);
        feed.add("RETURN", "Collect van(s) returned to origin hub — %d parcel(s) unloaded.".formatted(r.collected()));
    }

    /** Stage 5–6: reach AT_ORIGIN_HUB (M7 sorts onto a flight bag), then compressed freight → AT_DEST_HUB. */
    private void driveHubAndFreight(List<JourneyRecord> booked, LocalDate day) {
        // Stage 5 — origin hub: arriving at AT_ORIGIN_HUB triggers M7's outbound sort + flight bag.
        for (JourneyRecord jr0 : booked) {
            if (cancel) break;
            JourneyRecord jr = journeys.get(jr0.shipmentRef());
            if (jr.shipmentId() == null) continue;
            if (advanceTo(jr.shipmentRef(), ShipmentState.AT_ORIGIN_HUB)) {
                journeys.put(jr.shipmentRef(), jr.withStage("AT_ORIGIN_HUB"));
                feed.add("HUB", "%s ▸ arrived origin hub → M7 sorting onto flight bag".formatted(jr.shipmentRef()));
            }
        }
        settle(1500); // let M7's ShipmentStateConsumer sort the batch onto flight bags

        // Stage 6 — compressed freight: walk the air leg to AT_DEST_HUB (M9 states nobody produces yet).
        feed.add("FLIGHT", "✈ Flight departed — freight leg (compressed)…");
        for (JourneyRecord jr0 : booked) {
            journeys.computeIfPresent(jr0.shipmentRef(), (k, v) -> v.withStage("IN_FLIGHT"));
        }
        settle(2000);
        for (JourneyRecord jr0 : booked) {
            if (cancel) break;
            JourneyRecord jr = journeys.get(jr0.shipmentRef());
            if (jr.shipmentId() == null) continue;
            if (advanceTo(jr.shipmentRef(), ShipmentState.AT_DEST_HUB)) {
                journeys.put(jr.shipmentRef(), jr.withStage("AT_DEST_HUB"));
                feed.add("HUB", "%s ▸ landed, arrived dest hub → M7 inbound sort".formatted(jr.shipmentRef()));
            }
        }
    }

    /** Stage 7–8: wait for the Seam-B DELIVER binds, drive the dest-city deliver van, then complete → DROPPED. */
    private int driveDelivery(List<JourneyRecord> booked, UUID destCityId, LocalDate day, int tickMs) {
        var plan = vanDriver.approvedPlan(destCityId, day).orElse(null);
        if (plan == null) {
            feed.add("WARN", "No APPROVED plan for the dest city — deliver van cannot run (generate+approve a plan).");
            return 0;
        }
        settle(1500); // let M7's dest sort emit PARCEL_SORTED_FOR_DELIVERY (Seam B) for the batch
        int bound = vanDriver.waitForBinds(plan.getId(), booked.size(), () -> cancel);
        feed.add("BIND", "M6 bound %d parcel(s) to deliver loop(s) (Seam B).".formatted(bound));
        DemoVanDriver.DriveResult r = vanDriver.driveLoops(destCityId, day, tickMs, feed::add, () -> cancel);
        feed.add("SCAN", "Deliver van(s) handed %d parcel(s) to delivery DAs.".formatted(r.delivered()));

        // Stage 8 — complete the last-mile lifecycle to DROPPED (no auto DELIVERY task in v1, Q-M4-2).
        int delivered = 0;
        for (JourneyRecord jr0 : booked) {
            if (cancel) break;
            JourneyRecord jr = journeys.get(jr0.shipmentRef());
            if (jr.shipmentId() == null) continue;
            if (advanceTo(jr.shipmentRef(), ShipmentState.DROPPED)) {
                journeys.put(jr.shipmentRef(), jr.withStage("DELIVERED"));
                feed.add("DELIVER", "%s ▸ delivered to receiver → DROPPED".formatted(jr.shipmentRef()));
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Walk the shipment (by ref) forward along {@link #HAPPY_PATH} until it reaches {@code target}.
     * Tolerant of concurrent advances (M7/M4 event consumers may move it too): on an illegal transition
     * it re-reads the current state and continues. Returns true if {@code target} was reached (or passed).
     */
    private boolean advanceTo(String ref, ShipmentState target) {
        UUID shipmentId = journeys.get(ref).shipmentId();
        int targetIdx = HAPPY_PATH.indexOf(target);
        for (int guard = 0; guard < 60 && !cancel; guard++) {
            ShipmentState cur = shipmentLookupService.findByRef(ref).map(ShipmentInfo::state).orElse(null);
            int curIdx = cur == null ? -1 : HAPPY_PATH.indexOf(cur);
            if (curIdx < 0) {
                return false; // off the happy path (cancelled / failed) — stop
            }
            if (curIdx >= targetIdx) {
                return true;
            }
            ShipmentState next = HAPPY_PATH.get(curIdx + 1);
            try {
                stateMachine.transition(shipmentId, next, TransitionContext.fromSystem("demo-journey"));
            } catch (IllegalStateTransitionException e) {
                settle(200); // a consumer may have advanced it concurrently — re-read
            } catch (RuntimeException e) {
                feed.add("WARN", "advance %s → %s failed: %s".formatted(ref, next, e.getMessage()));
                settle(200);
            }
        }
        return false;
    }

    // ── booking helpers ──────────────────────────────────────────────────────────────────────────

    private BookingRequest buildRequest(String originCity, double[] origin, String destCity, double[] dest, int i) {
        BookingRequest req = new BookingRequest();
        req.setSenderName("Demo Sender " + i);
        req.setSenderPhone("+9190000000%02d".formatted(i % 100));
        req.setOriginAddress(address(originCity, origin));
        req.setOriginCity(originCity.toUpperCase());
        req.setOriginPincode("110001");
        req.setReceiverName("Demo Receiver " + i);
        req.setReceiverPhone("+9191000000%02d".formatted(i % 100));
        req.setDestAddress(address(destCity, dest));
        req.setDestCity(destCity.toUpperCase());
        req.setDestPincode("400001");
        req.setWeightGrams(1500);
        req.setLengthCm((short) 20);
        req.setWidthCm((short) 15);
        req.setHeightCm((short) 10);
        req.setDeclaredValuePaise(50_000L);
        req.setPickupType(PickupType.DA_PICKUP);
        req.setDropType(DropType.DA_DELIVERY);
        req.setPaymentMode(PaymentMode.PREPAID);
        return req;
    }

    private Address address(String city, double[] coord) {
        Address a = new Address();
        a.setLine1("Demo Line 1");
        a.setCity(city);
        a.setPincode(city.equalsIgnoreCase("mumbai") ? "400001" : "110001");
        a.setState("NA");
        a.setLatitude(coord[0]);
        a.setLongitude(coord[1]);
        return a;
    }

    /** A live in-grid coordinate for the city (a meeting vertex), so serviceability resolves to a hex. */
    private double[] aVertex(UUID cityId, String label) {
        List<GridVertexResponse> vertices = gridService.getVertices(cityId);
        if (vertices.isEmpty()) {
            throw new IllegalStateException("No grid vertices for " + label + " — seed the city grid first.");
        }
        GridVertexResponse v = vertices.get(0);
        return new double[]{v.lat(), v.lon()};
    }

    /**
     * Bring a city's shift DAs online by simulating each one's shift-start GPS heartbeat (placing the
     * DA at its cron meeting vertex). {@code updateGps} flips OFFLINE/ABSENT → IDLE, which is what the
     * assignment engine needs to treat a DA as a candidate. Returns the number pinged.
     */
    private int bringDasOnline(UUID cityId, LocalDate day) {
        List<DaCronAssignment> roster = cronRepository.findByOperatingDateAndCityId(day, cityId);
        Instant now = Instant.now(clock);
        for (DaCronAssignment c : roster) {
            daStatusService.updateGps(c.getDaId(), c.getMeetingLat(), c.getMeetingLon(), now);
        }
        return roster.size();
    }

    // ── status helpers ───────────────────────────────────────────────────────────────────────────

    private synchronized void setPhase(String phase, int booked, int assigned, int delivered) {
        status = new RunStatus(phase, status.originCity(), status.destCity(), status.date(),
                Math.max(booked, status.booked()), Math.max(assigned, status.assigned()),
                Math.max(delivered, status.delivered()), feed.lastSeq(), null);
    }

    private synchronized void bumpBooked() {
        status = new RunStatus(status.phase(), status.originCity(), status.destCity(), status.date(),
                status.booked() + 1, status.assigned(), status.delivered(), feed.lastSeq(), null);
    }

    private static String shortId(UUID id) {
        return id == null ? "—" : id.toString().substring(0, 8);
    }

    private void settle(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
