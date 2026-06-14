package com.oneday.pricing.adapter;

import com.oneday.common.port.PricingPort;
import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.pricing.domain.RateCard;
import com.oneday.pricing.service.PricingEngine;
import com.oneday.pricing.service.RateCardService;
import org.springframework.stereotype.Component;

/**
 * M2's implementation of the {@link PricingPort} contract M4 calls at booking time. Resolves the
 * applicable rate card + base price from the DB and delegates the arithmetic to {@link PricingEngine}.
 */
@Component
public class PricingPortAdapter implements PricingPort {

    private final RateCardService rateCardService;
    private final PricingEngine engine;

    public PricingPortAdapter(RateCardService rateCardService, PricingEngine engine) {
        this.rateCardService = rateCardService;
        this.engine = engine;
    }

    @Override
    public QuoteResult computeQuote(QuoteRequest request) {
        RateCard card = rateCardService.resolveForQuote(request);
        long basePrice = rateCardService.basePriceFor(
                card, request.originCity(), request.destCity(), request.deliveryType());
        return engine.price(card, basePrice, request.chargeableWeightGrams(),
                request.paymentMode(), request.declaredValuePaise());
    }
}
