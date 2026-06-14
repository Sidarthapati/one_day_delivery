package com.oneday.pricing.api;

import com.oneday.pricing.domain.RateCard;
import com.oneday.pricing.dto.CityPairRateResponse;
import com.oneday.pricing.dto.NewRateCardRequest;
import com.oneday.pricing.dto.RateCardResponse;
import com.oneday.pricing.service.RateCardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin rate-card management. Cards are versioned, never mutated in place: publishing supersedes
 * the prior active card of the same type. ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/pricing/admin/cards")
@PreAuthorize("hasRole('ADMIN')")
public class PricingAdminController {

    private final RateCardService rateCardService;

    public PricingAdminController(RateCardService rateCardService) {
        this.rateCardService = rateCardService;
    }

    @GetMapping
    public List<RateCardResponse> listCards() {
        return rateCardService.listCards().stream().map(RateCardResponse::from).toList();
    }

    @GetMapping("/{id}")
    public RateCardResponse getCard(@PathVariable UUID id) {
        return RateCardResponse.from(rateCardService.getCard(id));
    }

    @GetMapping("/{id}/rates")
    public List<CityPairRateResponse> listRates(@PathVariable UUID id) {
        return rateCardService.listPairRates(id).stream().map(CityPairRateResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<RateCardResponse> publish(@Valid @RequestBody NewRateCardRequest request) {
        RateCard card = rateCardService.publishNewVersion(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(RateCardResponse.from(card));
    }
}
