package com.oneday.pricing.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.pricing.config.PricingProperties;
import com.oneday.pricing.domain.CityPairRate;
import com.oneday.pricing.domain.RateCard;
import com.oneday.pricing.dto.NewRateCardRequest;
import com.oneday.pricing.repository.CityPairRateRepository;
import com.oneday.pricing.repository.RateCardRepository;
import com.oneday.pricing.service.CityCodes;
import com.oneday.pricing.service.NoRateConfiguredException;
import com.oneday.pricing.service.RateCardService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class RateCardServiceImpl implements RateCardService {

    static final String ACTIVE = "ACTIVE";
    static final String SUPERSEDED = "SUPERSEDED";

    private final RateCardRepository rateCards;
    private final CityPairRateRepository pairRates;
    private final PricingProperties props;

    RateCardServiceImpl(RateCardRepository rateCards, CityPairRateRepository pairRates,
                        PricingProperties props) {
        this.rateCards = rateCards;
        this.pairRates = pairRates;
        this.props = props;
    }

    @Override
    @Transactional(readOnly = true)
    public RateCard resolveForQuote(QuoteRequest req) {
        if (req.b2bRateCardId() != null) {
            return rateCards.findById(req.b2bRateCardId()).orElseThrow(() ->
                    new NoRateConfiguredException("No rate card " + req.b2bRateCardId()));
        }
        CustomerType type = req.customerType();
        return rateCards.findFirstByCustomerTypeAndStatus(type, ACTIVE).orElseThrow(() ->
                new NoRateConfiguredException("No active published rate card for " + type));
    }

    @Override
    @Transactional(readOnly = true)
    public long basePriceFor(RateCard card, String originCity, String destCity, DeliveryType deliveryType) {
        String origin = CityCodes.normalise(originCity);
        String dest = CityCodes.normalise(destCity);
        if (deliveryType == DeliveryType.SAME_CITY || origin.equals(dest)) {
            return card.getSameCityBasePricePaise();
        }
        return pairRates.findByRateCardIdAndOriginCityAndDestCity(card.getId(), origin, dest)
                .map(CityPairRate::getBasePricePaise)
                .orElseThrow(() -> new NoRateConfiguredException(
                        "No rate configured for " + origin + "→" + dest + " on card " + card.getCode()));
    }

    @Override
    @Transactional(readOnly = true)
    public RateCard activePublishedCard(CustomerType customerType) {
        return rateCards.findFirstByCustomerTypeAndStatus(customerType, ACTIVE).orElseThrow(() ->
                new NoRateConfiguredException("No active published rate card for " + customerType));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RateCard> listCards() {
        return rateCards.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public RateCard getCard(UUID id) {
        return rateCards.findById(id).orElseThrow(() ->
                new NoRateConfiguredException("No rate card " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CityPairRate> listPairRates(UUID cardId) {
        return pairRates.findByRateCardIdOrderByOriginCityAscDestCityAsc(cardId);
    }

    @Override
    @Transactional
    public RateCard publishNewVersion(NewRateCardRequest req) {
        // Supersede the prior ACTIVE published card of the same customer type.
        rateCards.findFirstByCustomerTypeAndStatus(req.customerType(), ACTIVE).ifPresent(prior -> {
            prior.setStatus(SUPERSEDED);
            prior.setEffectiveTo(Instant.now());
            rateCards.save(prior);
        });

        RateCard card = new RateCard();
        card.setCode(req.code());
        card.setCustomerType(req.customerType());
        card.setVersion(req.version());
        card.setStatus(ACTIVE);
        card.setEffectiveFrom(Instant.now());
        card.setCurrency(props.getCurrency());
        card.setSlabGrams(props.getSlabGrams());
        card.setVolumetricDivisor(props.getVolumetricDivisor());
        card.setFirstSlabPct(props.getFirstSlabPct());
        card.setSlabDecrementPct(props.getSlabDecrementPct());
        card.setSlabFloorPct(props.getSlabFloorPct());
        card.setGstBps(props.getGstBps());
        card.setCodPctBps(props.getCodPctBps());
        card.setCodMinPaise(props.getCodMinPaise());
        card.setDiscountBps(req.discountBps() == null ? 0 : req.discountBps());
        card.setSameCityBasePricePaise(req.sameCityBasePricePaise() == null
                ? props.getSameCityBasePricePaise() : req.sameCityBasePricePaise());
        RateCard saved = rateCards.save(card);

        for (NewRateCardRequest.PairRate p : req.pairs()) {
            String origin = CityCodes.normalise(p.originCity());
            String dest = CityCodes.normalise(p.destCity());
            savePair(saved.getId(), origin, dest, p.basePricePaise());
            if (req.symmetric() && !origin.equals(dest)) {
                savePair(saved.getId(), dest, origin, p.basePricePaise());
            }
        }
        return saved;
    }

    private void savePair(UUID cardId, String origin, String dest, long pricePaise) {
        CityPairRate rate = new CityPairRate();
        rate.setRateCardId(cardId);
        rate.setOriginCity(origin);
        rate.setDestCity(dest);
        rate.setBasePricePaise(pricePaise);
        pairRates.save(rate);
    }
}
