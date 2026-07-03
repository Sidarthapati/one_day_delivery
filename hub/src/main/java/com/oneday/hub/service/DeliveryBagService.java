package com.oneday.hub.service;

import com.oneday.hub.domain.BagKind;
import com.oneday.hub.domain.BagManifest;
import com.oneday.hub.domain.DeliveryBag;
import com.oneday.hub.domain.DeliveryBagItem;
import com.oneday.hub.domain.DeliveryBagItemStatus;
import com.oneday.hub.service.port.ShipmentInfoPort;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Delivery-bag lifecycle (§8.1, M7-D-012) — the destination mirror of {@link FlightBagService}. Lazy open
 * per route/territory/zone key, stage parcels with weight accumulation, seal → append-only load-list
 * manifest. M6's {@code VAN_LOAD} scan later drives the bag {@code → LOADED} (M7 stages, M6 loads).
 */
public interface DeliveryBagService {

    /**
     * Idempotently open (or fetch) the open delivery bag for a key. On first open a free stand is
     * allocated from the shared pool (dynamic, M7-D-001), preferring the delivery dock.
     */
    DeliveryBag openBag(OpenDeliveryBagCommand command);

    /** Stage a parcel into an open delivery bag; accumulates weight + count, records the ladder outputs. */
    DeliveryBagItem addParcel(UUID deliveryBagId, ShipmentInfoPort.ParcelInfo parcel,
                              UUID destHexId, UUID daTerritoryId, UUID routePlanId);

    /** Seal an open delivery bag → append-only LOAD_LIST manifest, freeze contents (BAG_SEALED). */
    SealResult seal(UUID deliveryBagId);

    /** Read a delivery bag by id. */
    DeliveryBag bag(UUID deliveryBagId);

    /** Operator console: the live delivery bags at a hub for a day (the dest directory — what a van loads). */
    List<DeliveryBag> deliveryBags(UUID hubId, LocalDate date);

    /** Operator console: per-parcel staging view for a city, by item status. */
    List<DeliveryBagItem> staging(UUID cityId, DeliveryBagItemStatus status);

    record OpenDeliveryBagCommand(
            UUID cityId,
            UUID hubId,
            BagKind bagKind,
            LocalDate bagDate,
            UUID routePlanId,
            UUID vanId,
            UUID daTerritoryId,
            UUID zoneId) {
    }

    record SealResult(DeliveryBag bag, BagManifest manifest) {
    }
}
