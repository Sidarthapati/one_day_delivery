package com.oneday.pricing.api;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.pricing.adapter.PricingPortAdapter;
import com.oneday.pricing.domain.RateCard;
import com.oneday.pricing.dto.CityPairRateResponse;
import com.oneday.pricing.dto.PublishedTariffResponse;
import com.oneday.pricing.service.RateCardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public pricing reads: a quote preview (same computation M4 invokes via
 * {@link com.oneday.common.port.PricingPort} at booking) and the published rate card / tariff that a
 * customer can browse to understand the costing.
 */
@RestController
@RequestMapping("/api/v1/pricing")
public class PricingController {

    private final PricingPortAdapter pricing;
    private final RateCardService rateCardService;

    public PricingController(PricingPortAdapter pricing, RateCardService rateCardService) {
        this.pricing = pricing;
        this.rateCardService = rateCardService;
    }

    @PostMapping("/quote")
    public ResponseEntity<QuoteResult> quote(@RequestBody QuoteRequest request) {
        return ResponseEntity.ok(pricing.computeQuote(request));
    }

    /** Published tariff: the active card's parameters + full city-pair base-price matrix. */
    @GetMapping("/rate-card")
    public ResponseEntity<PublishedTariffResponse> rateCard(
            @RequestParam(name = "customer_type", defaultValue = "B2C") CustomerType customerType) {
        RateCard card = rateCardService.activePublishedCard(customerType);
        List<CityPairRateResponse> rates = rateCardService.listPairRates(card.getId()).stream()
                .map(CityPairRateResponse::from)
                .toList();
        return ResponseEntity.ok(PublishedTariffResponse.from(card, rates));
    }
}
