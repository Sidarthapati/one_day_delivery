package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.MerchantCategory;
import com.oneday.orders.dto.MerchantAnalyticsResponse;
import com.oneday.orders.dto.MerchantAnalyticsResponse.CategoryCount;
import com.oneday.orders.dto.MerchantAnalyticsResponse.DestinationCount;
import com.oneday.orders.repository.AccountTotals;
import com.oneday.orders.repository.MerchantCategoryRepository;
import com.oneday.orders.repository.OnTimeStat;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.StateCount;
import com.oneday.orders.service.MerchantAnalyticsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
class MerchantAnalyticsServiceImpl implements MerchantAnalyticsService {

    /** Delivered terminal states — DA drop-off and receiver hub-collect both count as delivered. */
    private static final Set<ShipmentState> DELIVERED =
            EnumSet.of(ShipmentState.DROPPED, ShipmentState.HUB_COLLECTED);
    private static final Set<ShipmentState> RTO =
            EnumSet.of(ShipmentState.RTO_INITIATED, ShipmentState.RTO_IN_TRANSIT, ShipmentState.RTO_COMPLETED);
    /** Busiest few destinations are enough for the dashboard; there are only 5 serviceable cities today. */
    private static final int TOP_DESTINATIONS = 6;

    private static final String UNCATEGORISED = "Uncategorised";
    private static final String OTHER = "Other";
    /** Cap the named-category rows so a merchant who (ab)uses categories as per-order tags can't blow up the
     *  payload or the chart; everything past the busiest few folds into "Other". */
    private static final int TOP_CATEGORIES = 10;

    private final ShipmentRepository shipments;
    private final MerchantCategoryRepository categories;

    MerchantAnalyticsServiceImpl(ShipmentRepository shipments, MerchantCategoryRepository categories) {
        this.shipments = shipments;
        this.categories = categories;
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantAnalyticsResponse forAccount(UUID accountId, Integer windowDays) {
        // EPOCH sentinel for all-time — a null bind in a "? IS NULL" SQL branch left Postgres unable to
        // infer the parameter's type; no shipment predates 1970, so this is equivalent and keeps the SQL simple.
        Instant since = windowDays == null ? Instant.EPOCH : Instant.now().minus(windowDays, ChronoUnit.DAYS);

        long total = 0, delivered = 0, cancelled = 0, rto = 0;
        for (StateCount sc : shipments.countByStateForAccount(accountId, since)) {
            long c = sc.getCount();
            total += c;
            ShipmentState st = sc.getState();
            if (DELIVERED.contains(st)) delivered += c;
            else if (st == ShipmentState.CANCELLED) cancelled += c;
            else if (RTO.contains(st)) rto += c;
        }
        // ponytail: in-transit is the remainder — transient failure states (PICKUP_FAILED/DELIVERY_FAILED)
        // fold in here as "still moving" rather than getting their own bucket. Split them out if merchants
        // ask to see stuck parcels separately.
        long inTransit = total - delivered - cancelled - rto;

        long rateable = total - cancelled; // a cancelled parcel was never a delivery attempt
        Integer deliveryRatePct = rateable > 0 ? Math.round(delivered * 100f / rateable) : null;

        AccountTotals totals = shipments.sumTotalsForAccount(accountId, since);
        long gmv = totals == null ? 0 : totals.getGmvPaise();
        long cod = totals == null ? 0 : totals.getCodPaise();
        long avg = total > 0 ? gmv / total : 0;

        OnTimeStat ot = shipments.onTimeForAccount(accountId, DELIVERED, since);
        Integer onTimePct = (ot != null && ot.getDelivered() > 0)
                ? Math.round(ot.getOnTime() * 100f / ot.getDelivered()) : null;

        List<DestinationCount> dests = shipments.destinationSplitForAccount(accountId, since).stream()
                .limit(TOP_DESTINATIONS)
                .map(cc -> new DestinationCount(cc.getCity(), cc.getCount()))
                .toList();

        List<CategoryCount> categorySplit = categorySplit(accountId, since);

        return new MerchantAnalyticsResponse(windowDays, total, delivered, inTransit, cancelled, rto,
                deliveryRatePct, onTimePct, gmv, cod, avg, dests, categorySplit);
    }

    /**
     * Shipments per merchant category, busiest first. The split query groups by {@code category_id}; here
     * we resolve ids to names, folding untagged parcels and any since-deleted category into "Uncategorised".
     */
    private List<CategoryCount> categorySplit(UUID accountId, Instant since) {
        Map<UUID, String> names = new HashMap<>();
        for (MerchantCategory c : categories.findByB2bAccountIdOrderByName(accountId)) {
            names.put(c.getId(), c.getName());
        }
        // Named-category totals (archived categories still resolve to their real name), plus the untagged
        // bucket kept aside — "Uncategorised" is meaningful and always shown, never folded into "Other".
        Map<String, Long> named = new LinkedHashMap<>();
        long uncategorised = 0;
        for (var row : shipments.categorySplitForAccount(accountId, since)) {
            if (row.getCategoryId() == null) {
                uncategorised += row.getCount();
                continue;
            }
            named.merge(names.getOrDefault(row.getCategoryId(), UNCATEGORISED), row.getCount(), Long::sum);
        }
        // A resolved name may itself be "Uncategorised" (a since-deleted id we couldn't resolve) — treat it
        // the same as untagged rather than as a named category.
        uncategorised += named.getOrDefault(UNCATEGORISED, 0L);
        named.remove(UNCATEGORISED);

        List<Map.Entry<String, Long>> sorted = named.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed())
                .toList();

        List<CategoryCount> out = new ArrayList<>();
        long other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < TOP_CATEGORIES) {
                out.add(new CategoryCount(sorted.get(i).getKey(), sorted.get(i).getValue()));
            } else {
                other += sorted.get(i).getValue();   // everything past the busiest few
            }
        }
        if (other > 0) {
            out.add(new CategoryCount(OTHER, other));
        }
        if (uncategorised > 0) {
            out.add(new CategoryCount(UNCATEGORISED, uncategorised));
        }
        return out;
    }
}
