package com.oneday.orders.tracking;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the customer-facing tracking timeline: the states the shipment has actually reached (from the
 * append-only {@code shipment_state_history}, with real timestamps) followed by the checkpoints still
 * to come (from a per-shipment template shaped by delivery/pickup/drop type). Terminal shipments show
 * no pending steps. Labels come from {@link CustomerVisibleStateMapper} so the timeline and the rest
 * of the product speak the same language.
 */
@Component
public class MilestoneBuilder {

    private static final Set<ShipmentState> TERMINAL = Set.of(
            ShipmentState.DROPPED, ShipmentState.HUB_COLLECTED,
            ShipmentState.RTO_COMPLETED, ShipmentState.CANCELLED);

    private final ShipmentStateHistoryRepository historyRepository;
    private final CustomerVisibleStateMapper stateMapper;

    MilestoneBuilder(ShipmentStateHistoryRepository historyRepository, CustomerVisibleStateMapper stateMapper) {
        this.historyRepository = historyRepository;
        this.stateMapper = stateMapper;
    }

    public List<Milestone> build(Shipment s) {
        // Done spine: every reached state that carries a customer label, deduped by label (keep the
        // earliest occurrence), in the order they happened.
        Map<String, Milestone> done = new LinkedHashMap<>();
        for (ShipmentStateHistory h : historyRepository.findByShipmentIdOrderByOccurredAtAsc(s.getId())) {
            String label = stateMapper.labelFor(h.getToState());
            done.putIfAbsent(label, new Milestone(label, h.getOccurredAt(), true));
        }

        List<Milestone> out = new ArrayList<>(done.values());

        // Pending checkpoints: the remaining expected steps, unless the shipment is already terminal.
        if (!TERMINAL.contains(s.getState())) {
            for (ShipmentState step : template(s)) {
                String label = stateMapper.labelFor(step);
                if (!done.containsKey(label)) {
                    out.add(new Milestone(label, null, false));
                    done.put(label, null); // guard against duplicate pending labels within the template
                }
            }
        }
        return out;
    }

    /** The expected happy-path checkpoints for this shipment, shaped by its routing choices. */
    private static List<ShipmentState> template(Shipment s) {
        List<ShipmentState> t = new ArrayList<>();
        t.add(ShipmentState.BOOKED);
        t.add(s.getPickupType() == PickupType.SELF_DROP
                ? ShipmentState.AWAITING_SELF_DROP
                : ShipmentState.PICKED_UP);
        t.add(ShipmentState.AT_ORIGIN_HUB);
        if (s.getDeliveryType() == DeliveryType.INTERCITY) {
            t.add(ShipmentState.DEPARTED);      // "In transit by air"
            t.add(ShipmentState.AT_DEST_HUB);
        }
        if (s.getDropType() == DropType.HUB_COLLECT) {
            t.add(ShipmentState.AWAITING_HUB_COLLECT);
            t.add(ShipmentState.HUB_COLLECTED);
        } else {
            t.add(ShipmentState.HANDED_TO_DROP_VAN);  // "Out for delivery"
            t.add(ShipmentState.DROPPED);
        }
        return t;
    }

    /** One row in the tracking timeline. {@code occurredAt} is null for a step not yet reached. */
    public record Milestone(String label, Instant occurredAt, boolean done) {}
}
