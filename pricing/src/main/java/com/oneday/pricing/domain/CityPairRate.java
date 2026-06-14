package com.oneday.pricing.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Base price (paise) for the first weight slab of an origin→destination city pair on a given rate
 * card. Rows are seeded in both directions (the published sheet is symmetric). Append-only in
 * practice: a rate change ships as a new {@link RateCard} version with its own pair rows.
 */
@Entity
@Table(name = "city_pair_rate")
@Getter
@Setter
@NoArgsConstructor
public class CityPairRate extends BaseEntity {

    @Column(name = "rate_card_id", nullable = false)
    private UUID rateCardId;

    @Column(name = "origin_city", length = 10, nullable = false)
    private String originCity;

    @Column(name = "dest_city", length = 10, nullable = false)
    private String destCity;

    /** Price in paise for the first slab (0.5 kg) of this pair. */
    @Column(name = "base_price_paise", nullable = false)
    private long basePricePaise;
}
