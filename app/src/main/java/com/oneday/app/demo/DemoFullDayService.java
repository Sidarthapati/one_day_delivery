package com.oneday.app.demo;

import com.oneday.common.domain.MeetingMode;
import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.service.FlightBagService;
import com.oneday.hub.service.HubReceivingService;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.auth.repository.UserRepository;
import com.oneday.routing.demo.DemoExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demo-only ({@code @Profile("!prod")}) <b>one-button intercity end-to-end</b> orchestrator. Lives in
 * {@code app} — the composition root — because it is the only module that can compose M4 (orders),
 * M5 (dispatch), M6 (routing), and M7 (hub) at once.
 *
 * <p>Two buttons drive it:
 * <ul>
 *   <li>{@code POST /api/demo/order} — place N <b>real</b> intercity bookings A→B (emits
 *       {@code ShipmentCreatedEvent} → M5 auto-assigns the pickup DA).</li>
 *   <li>{@code POST /api/demo/full-day/run} — carry those parcels through the whole real chain in one
 *       go: pickup + OTP → first-mile van → <b>real M7 origin hub</b> (receive → stand → flight bag →
 *       manifest → dispatch) → simulated flight → <b>real M7 dest hub</b> (receive → delivery bag →
 *       real {@code ParcelSortedForDelivery} that M6 binds) → drop van → delivery DA → delivered.</li>
 * </ul>
 *
 * <p><b>Why M4 states are driven here, not by hub events:</b> the hub emits {@code HubEventPayload}s
 * (BagCreated/StandAssigned/DestSortComplete/…), but M4's {@code HubEventsConsumer} only acts on the
 * {@code HubEvent} record, which is never on the wire — so the hub→M4 state-advance seam is currently
 * inert. The M7 <b>sortation</b> (bags, manifests, delivery bags) and the M6 <b>feed</b>
 * ({@code ParcelSortedForDelivery}) are fully real; only the M4 state transitions are advanced
 * deterministically here, exactly as the existing {@code dropPath} demo already does. (Follow-up:
 * make the hub→M4 seam real by emitting/consuming a per-parcel {@code HubEvent}.)</p>
 */
@Service
@Profile("!prod")
public class DemoFullDayService {

    private static final Logger log = LoggerFactory.getLogger(DemoFullDayService.class);

    /** The air/shuttle leg M9 will own — simulated here (no producer exists yet). */
    private static final List<ShipmentState> FLIGHT_LEG = List.of(
            ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT, ShipmentState.DEPARTED,
            ShipmentState.LANDED, ShipmentState.DISPATCHED_TO_HUB, ShipmentState.AT_DEST_HUB);

    private final ShipmentRepository shipments;
    private final ShipmentStateMachine stateMachine;
    private final BookingService bookingService;
    private final UserRepository userRepository;
    private final HubReceivingService hubReceiving;
    private final FlightBagService flightBags;
    private final DemoExecutionService demoExec;   // shared feed/status + van animation
    private final DemoDaController demoDa;          // reuse proven pickup/drop phase helpers
    private final CityMeetingModePort meetingMode;  // per-city M6 gate: van meeting vs hub return

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-full-day");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Progress progress = Progress.idle();

    public DemoFullDayService(ShipmentRepository shipments, ShipmentStateMachine stateMachine,
                              BookingService bookingService, UserRepository userRepository,
                              HubReceivingService hubReceiving, FlightBagService flightBags,
                              DemoExecutionService demoExec, DemoDaController demoDa,
                              CityMeetingModePort meetingMode) {
        this.shipments = shipments;
        this.stateMachine = stateMachine;
        this.bookingService = bookingService;
        this.userRepository = userRepository;
        this.hubReceiving = hubReceiving;
        this.flightBags = flightBags;
        this.demoExec = demoExec;
        this.demoDa = demoDa;
        this.meetingMode = meetingMode;
    }

    private boolean isHubReturn(UUID cityId) {
        return meetingMode.modeFor(cityId) == MeetingMode.HUB_RETURN;
    }

    // ── public surface ──────────────────────────────────────────────────────────────────────────

    public record PlaceResult(int placed, int skipped, List<String> refs, String message) {}

    /** Coarse macro-phase for the UI phase tracker (van counts still come from /api/demo/run-status). */
    public record Progress(String phase, String detail, int total, int atOriginHub, int inFlight,
                           int atDestHub, int delivered, boolean running, String error) {
        static Progress idle() {
            return new Progress("IDLE", "", 0, 0, 0, 0, 0, false, null);
        }
    }

    public Progress status() {
        return progress;
    }

    /**
     * Place {@code count} real intercity bookings A→B: pickup at an origin-city DA-territory centroid,
     * drop at a dest-city DA-territory centroid, so first-mile (A), dest-hub sort (B) and M6 binding (B)
     * all resolve. Each booking emits {@code ShipmentCreatedEvent} → M5 assigns the pickup DA.
     */
    public PlaceResult placeOrders(UUID originCityId, String originCity, String originPin,
                                   UUID destCityId, String destCity, String destPin,
                                   int count, LocalDate date) {
        List<double[]> pickPts = demoDa.territoryCentroids(originCityId, date);
        List<double[]> dropPts = demoDa.territoryCentroids(destCityId, date);
        if (pickPts.isEmpty() || dropPts.isEmpty()) {
            throw new IllegalStateException("Origin or destination city has no DA territories — prepare both cities first.");
        }
        String userId = userRepository.findByEmail("b2c@demo.in").map(u -> u.getId().toString())
                .orElse(UUID.randomUUID().toString());

        List<String> refs = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < count; i++) {
            double[] pick = wrap(pickPts, i);
            double[] drop = wrap(dropPts, i);
            try {
                BookingRequest req = demoDa.booking(pick, originCity, originPin, drop, destCity, destPin);
                BookingResponse r = bookingService.book(req, UUID.randomUUID().toString(), userId, CustomerType.B2C);
                refs.add(r.getShipmentRef());
            } catch (RuntimeException e) {
                skipped++;   // outside grid / unpriced route → skip
            }
        }
        String msg = refs.size() + " order(s) placed " + originCity + " → " + destCity
                + " — M5 is auto-assigning each pickup to a DA in " + originCity
                + (skipped > 0 ? " (" + skipped + " skipped)" : "");
        demoExec.feed("ORDER", "🧾 " + msg);
        return new PlaceResult(refs.size(), skipped, refs, msg);
    }

    /** Nudge each wrap-around lap ~75 m so overlapping points don't stack on the same hex/DA. */
    private static double[] wrap(List<double[]> base, int i) {
        int lap = i / base.size();
        double[] c = base.get(i % base.size());
        return lap == 0 ? c : new double[]{c[0] + lap * 0.0007, c[1] + lap * 0.0007};
    }

    /** Kick off the full intercity run on a background thread (single run at a time). */
    public synchronized Progress run(UUID originCityId, String originCity,
                                     UUID destCityId, String destCity, LocalDate date, int speed) {
        if (running.get()) {
            throw new IllegalStateException("A full-day run is already in progress.");
        }
        running.set(true);
        progress = new Progress("STARTING", "Preparing the intercity run…", 0, 0, 0, 0, 0, true, null);
        runner.submit(() -> runAll(originCityId, originCity, destCityId, destCity, date, speed));
        return progress;
    }

    // ── the orchestration ─────────────────────────────────────────────────────────────────────────

    private void runAll(UUID aCityId, String aCity, UUID bCityId, String bCity, LocalDate date, int speed) {
        try {
            demoExec.feed("PHASE", "▶ Full-day intercity run: %s → %s. One button, whole chain.".formatted(aCity, bCity));

            // ── PHASE 1 — first-mile pickups in A ───────────────────────────────────────────────
            phase("FIRST_MILE", "1/5 First-mile pickups in " + aCity);
            List<Shipment> batch = intercity(aCity, bCity, ShipmentState.BOOKED, ShipmentState.PICKUP_ASSIGNED);
            // Scope this run's counts to the parcels it actually starts with (the just-placed A→B orders),
            // so the live bars and the final "delivered" tally aren't inflated by every DROPPED parcel ever
            // sent to B on earlier runs/days.
            java.util.Set<UUID> runIds = new java.util.LinkedHashSet<>();
            for (Shipment sh : batch) runIds.add(sh.getId());
            demoExec.feed("PHASE", "① Assigning %d pickup(s) to DAs in %s + minting OTPs…".formatted(batch.size(), aCity));
            for (Shipment sh : batch) {
                try { demoDa.assignPickup(sh.getShipmentRef()); } catch (RuntimeException ignore) { /* already assigned */ }
            }
            demoDa.autoVerify(aCityId, date);                       // → PICKED_UP + M5 task IN_PROGRESS
            if (isHubReturn(aCityId)) {
                // HUB_RETURN origin: no van meets the DA — the DA carries the collected pickups back to the
                // hub on its periodic return (→ RETURNED_TO_HUB, not HANDED_TO_PICKUP_VAN). M6 binding is
                // skipped for this city; there's nothing to animate. M5 assignment + our OTP handshake are
                // async, so wait for this run's parcels to actually reach PICKED_UP before routing them the
                // hub-return way — otherwise the downstream straggler path would force the van state.
                List<Shipment> pickedUp = awaitPickedUp(runIds, aCityId, date);
                for (Shipment sh : pickedUp) {
                    advance(sh.getId(), sh.getShipmentRef(), ShipmentState.RETURNED_TO_HUB);
                }
                demoExec.feed("PHASE", "① %s is HUB_RETURN — DAs carried the pickups back to the hub themselves (→ RETURNED_TO_HUB, no first-mile van).".formatted(aCity));
            } else {
                demoExec.feed("PHASE", "① OTP handshakes done — driving the first-mile van(s) to collect from DAs…");
                demoExec.start(aCityId, date, 0, batch.size(), speed);  // animate collect run (real M5→M6 collect bridge)
                awaitVanIdle();
                demoExec.returnToHub(aCityId, date);                    // drive vans home (map)
                awaitVanIdle();
            }
            demoDa.pickupsToHub(aCity, date);                       // → AT_ORIGIN_HUB

            // ── PHASE 2 — REAL M7 origin hub in A ────────────────────────────────────────────────
            phase("ORIGIN_HUB", "2/5 Origin hub sortation in " + aCity);
            List<Shipment> atHub = intercity(aCity, bCity, ShipmentState.AT_ORIGIN_HUB);
            java.util.LinkedHashSet<UUID> bagIds = new java.util.LinkedHashSet<>();
            for (Shipment sh : atHub) {
                try {
                    var res = hubReceiving.receive(aCityId, sh.getShipmentRef());   // REAL M7 outbound sort
                    if (res.sort() != null) {
                        // Put the parcel INTO its flight bag so the bag + manifest actually contain it
                        // (resolveOutbound assigns the stand/bag but leaves adding contents to the operator).
                        flightBags.addParcel(res.sort().bagId(), sh.getShipmentRef());
                        bagIds.add(res.sort().bagId());
                        demoExec.feed("HUB", "🏭 %s received at %s hub → stand %s, into flight bag %s"
                                .formatted(sh.getShipmentRef(), aCity, res.sort().standNo(), res.sort().flightNo()));
                    }
                    advance(sh.getId(), sh.getShipmentRef(), ShipmentState.ORIGIN_HUB_PROCESSING);
                    advance(sh.getId(), sh.getShipmentRef(), ShipmentState.IN_TAKEOFF_BAG);
                    settle(120);
                } catch (RuntimeException e) {
                    demoExec.feed("ERROR", "Origin sort failed for %s: %s".formatted(sh.getShipmentRef(), e.getMessage()));
                }
            }
            sealAndDispatchBags(aCity, bagIds);   // seal + dispatch exactly this run's bags (with real contents)
            updateCounts(runIds);

            // ── PHASE 3 — simulated flight A → B ─────────────────────────────────────────────────
            phase("FLIGHT", "3/5 Flight " + aCity + " → " + bCity);
            for (Shipment sh : intercity(aCity, bCity, ShipmentState.IN_TAKEOFF_BAG)) {
                flightLeg(sh);
                updateCounts(runIds);
                settle(150);
            }
            demoExec.feed("FLIGHT", "🛬 Flight(s) landed at %s — parcels shuttled to the destination hub.".formatted(bCity));

            // ── PHASE 4 — REAL M7 dest hub in B (emits the real M6 feed) ─────────────────────────
            phase("DEST_HUB", "4/5 Destination hub sortation in " + bCity);
            demoExec.resetForCity(bCityId, date);                  // clean manifests before the real feed
            int fed = 0;
            for (Shipment sh : intercity(aCity, bCity, ShipmentState.AT_DEST_HUB)) {
                try {
                    hubReceiving.receive(bCityId, sh.getShipmentRef());   // REAL M7 inbound sort → ParcelSortedForDelivery + delivery bag
                    demoExec.feed("HUB", "🏭 %s sorted at %s hub → delivery bag; emitted ParcelSortedForDelivery (M6 binds it)"
                            .formatted(sh.getShipmentRef(), bCity));
                    advance(sh.getId(), sh.getShipmentRef(), ShipmentState.DEST_HUB_PROCESSING);
                    fed++;
                    settle(120);
                } catch (RuntimeException e) {
                    demoExec.feed("ERROR", "Dest sort failed for %s: %s".formatted(sh.getShipmentRef(), e.getMessage()));
                }
            }

            // ── PHASE 5 — last-mile delivery in B ────────────────────────────────────────────────
            phase("LAST_MILE", "5/5 Last-mile delivery in " + bCity);
            if (isHubReturn(bCityId)) {
                // HUB_RETURN dest: no drop van. M5's HubDeliveryFeedConsumer already assigned each sorted
                // parcel to its territory DA, who collects it at the hub — drive the honest hub states
                // (HUB_DELIVERY_ASSIGNED → COLLECTED_FROM_HUB → DROPPED), never the van states.
                for (Shipment sh : intercity(aCity, bCity, ShipmentState.DEST_HUB_PROCESSING)) {
                    advance(sh.getId(), sh.getShipmentRef(), ShipmentState.HUB_DELIVERY_ASSIGNED);
                }
                demoExec.feed("PHASE", "⑤ %s is HUB_RETURN — the %d delivery(ies) were assigned to territory DAs who collect them at the hub (→ HUB_DELIVERY_ASSIGNED, no drop van).".formatted(bCity, fed));
            } else {
                // HANDED_TO_DROP_VAN emits the event M5 reacts to (assignDelivery → drop DA), then onto the van.
                for (Shipment sh : intercity(aCity, bCity, ShipmentState.DEST_HUB_PROCESSING)) {
                    advance(sh.getId(), sh.getShipmentRef(), ShipmentState.HANDED_TO_DROP_VAN);
                }
                settle(500);                                           // let M5 assign the delivery DA
                for (Shipment sh : intercity(aCity, bCity, ShipmentState.HANDED_TO_DROP_VAN)) {
                    advance(sh.getId(), sh.getShipmentRef(), ShipmentState.DROP_ASSIGNED);
                }
                demoExec.feed("PHASE", "⑤ Driving the drop van(s) over the %d hub-sorted delivery(ies)…".formatted(fed));
                demoExec.driveDeliveries(bCityId, date, fed, speed);   // bind (real feed) + animate
                awaitVanIdle();
            }
            demoDa.dropsCollected(bCity, date);                    // → DROP_COLLECTED / COLLECTED_FROM_HUB (OTP minted)
            demoDa.autoVerifyDeliveries(bCity, date);              // → DROPPED
            updateCounts(runIds);

            int delivered = countInRun(runIds, ShipmentState.DROPPED);
            demoExec.feed("PHASE", "✅ Full-day run complete — %d parcel(s) delivered %s → %s.".formatted(delivered, aCity, bCity));
            progress = new Progress("DONE", "Delivered " + delivered + " parcel(s) " + aCity + " → " + bCity,
                    runIds.size(), 0, 0, 0, delivered, false, null);
        } catch (RuntimeException e) {
            log.error("Full-day run failed", e);
            demoExec.feed("ERROR", "Full-day run failed: " + e.getMessage());
            progress = new Progress("ERROR", e.getMessage(), progress.total(), progress.atOriginHub(),
                    progress.inFlight(), progress.atDestHub(), progress.delivered(), false, e.getMessage());
        } finally {
            running.set(false);
        }
    }

    // ── phase helpers ─────────────────────────────────────────────────────────────────────────────

    /** Seal + dispatch exactly this run's flight bags (the airport-shuttle handoff), skipping empties. */
    private void sealAndDispatchBags(String city, java.util.Set<UUID> bagIds) {
        for (UUID bagId : bagIds) {
            try {
                FlightBag bag = flightBags.bag(bagId);
                if (bag.getStatus() != FlightBagStatus.OPEN) continue;
                var sealed = flightBags.seal(bagId);
                flightBags.dispatch(bagId);
                demoExec.feed("HUB", "📦 %s hub — flight bag %s sealed (%d parcel(s) in manifest) → dispatched to airport shuttle."
                        .formatted(city, bag.getFlightNo(), sealed.manifest().getParcelCount()));
            } catch (RuntimeException e) {
                demoExec.feed("ERROR", "Bag seal/dispatch failed: " + e.getMessage());
            }
        }
    }

    /** Simulate the air leg for one parcel (M9's future job) — IN_TAKEOFF_BAG → AT_DEST_HUB. */
    private void flightLeg(Shipment sh) {
        for (ShipmentState target : FLIGHT_LEG) {
            advance(sh.getId(), sh.getShipmentRef(), target);
            if (target == ShipmentState.DEPARTED) {
                demoExec.feed("FLIGHT", "✈ %s departed on the flight.".formatted(sh.getShipmentRef()));
            }
        }
    }

    /** Advance M4 one step; tolerant (a racing/duplicate transition is ignored so a run never aborts). */
    private void advance(UUID shipmentId, String ref, ShipmentState target) {
        try {
            stateMachine.transition(shipmentId, target, TransitionContext.fromApi("demo-full-day", ref));
        } catch (RuntimeException e) {
            log.debug("skip transition {} → {}: {}", ref, target, e.getMessage());
        }
    }

    // ── queries / status ────────────────────────────────────────────────────────────────────────

    /** Shipments of this run: intercity A→B in any of the given states. */
    private List<Shipment> intercity(String originCity, String destCity, ShipmentState... states) {
        List<Shipment> out = new ArrayList<>();
        for (ShipmentState st : states) {
            for (Shipment sh : shipments.findByState(st)) {
                if (eq(sh.getOriginCity(), originCity) && eq(sh.getDestCity(), destCity)) out.add(sh);
            }
        }
        return out;
    }

    /**
     * Wait for this run's parcels to reach PICKED_UP. M5's pickup assignment and our OTP handshake are
     * async (event-driven), so a fast one-button run can outrun them; re-run the demo auto-verify until the
     * run's parcels are collected (bounded, ~20s). Returns the run's parcels now in PICKED_UP.
     */
    private List<Shipment> awaitPickedUp(java.util.Set<UUID> runIds, UUID cityId, LocalDate date) {
        List<Shipment> picked = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            try { demoDa.autoVerify(cityId, date); } catch (RuntimeException ignore) { /* keep polling */ }
            picked = new ArrayList<>();
            for (Shipment sh : shipments.findByState(ShipmentState.PICKED_UP)) {
                if (runIds.contains(sh.getId())) picked.add(sh);
            }
            if (picked.size() >= runIds.size()) break;   // all collected
            settle(500);
        }
        return picked;
    }

    /** Count parcels of THIS run (captured at PHASE 1) currently in state {@code st}. */
    private int countInRun(java.util.Set<UUID> runIds, ShipmentState st) {
        int n = 0;
        for (Shipment sh : shipments.findByState(st)) {
            if (runIds.contains(sh.getId())) n++;
        }
        return n;
    }

    private void updateCounts(java.util.Set<UUID> runIds) {
        int origin = countInRun(runIds, ShipmentState.IN_TAKEOFF_BAG);
        int flight = 0;
        for (ShipmentState st : FLIGHT_LEG) flight += countInRun(runIds, st);
        int destHub = countInRun(runIds, ShipmentState.DEST_HUB_PROCESSING);
        int delivered = countInRun(runIds, ShipmentState.DROPPED);
        progress = new Progress(progress.phase(), progress.detail(), progress.total(),
                origin, flight, destHub, delivered, running.get(), null);
    }

    private void phase(String phase, String detail) {
        demoExec.feed("PHASE", "▸ " + detail);
        progress = new Progress(phase, detail, progress.total(), progress.atOriginHub(),
                progress.inFlight(), progress.atDestHub(), progress.delivered(), true, null);
    }

    /** Block until the shared van animation (start/driveDeliveries/returnToHub) drains, with a safety cap. */
    private void awaitVanIdle() {
        for (int i = 0; i < 600 && demoExec.isRunning(); i++) settle(100);   // ≤60s
    }

    private static boolean eq(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private void settle(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
