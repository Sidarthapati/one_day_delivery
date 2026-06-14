package com.oneday.pricing.service;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.pricing.domain.CityPairRate;
import com.oneday.pricing.domain.RateCard;
import com.oneday.pricing.dto.NewRateCardRequest;

import java.util.List;
import java.util.UUID;

/**
 * Resolves which {@link RateCard} and base price apply to a quote, and owns admin rate-card
 * lifecycle (publish-new-version with supersede). Versioning is append-only: an existing card is
 * never re-priced, so historical shipments always reconcile against the version snapshot stored on
 * them (M2-D-002).
 */
public interface RateCardService {

    /** Resolve the rate card for a quote: the account card if {@code b2bRateCardId} is set, else the
     *  active published card for the customer type. */
    RateCard resolveForQuote(QuoteRequest req);

    /** Base price (paise) for the first slab of the pair on this card; same-city uses the card's
     *  same-city base. Throws {@link NoRateConfiguredException} if no pair rate exists. */
    long basePriceFor(RateCard card, String originCity, String destCity, DeliveryType deliveryType);

    /** The single ACTIVE published card for a customer type (B2C/C2C). For the customer-facing tariff. */
    RateCard activePublishedCard(CustomerType customerType);

    List<RateCard> listCards();

    RateCard getCard(UUID id);

    List<CityPairRate> listPairRates(UUID cardId);

    /** Admin: insert a new ACTIVE published card (B2C/C2C) with its full city-pair matrix, flipping
     *  the prior ACTIVE card of the same customer type to SUPERSEDED. */
    RateCard publishNewVersion(NewRateCardRequest request);
}
