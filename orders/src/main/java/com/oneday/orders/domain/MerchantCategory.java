package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A merchant-defined section/category ("Electronics", "Apparel"). Account-scoped: the API only ever
 * reads/writes rows for the authenticated caller's own b2b_account_id.
 */
@Entity
@Table(name = "merchant_category")
@Getter
@Setter
@NoArgsConstructor
public class MerchantCategory extends MutableBaseEntity {

    @Column(name = "b2b_account_id", nullable = false, updatable = false)
    private UUID b2bAccountId;

    @Column(name = "name", length = 60, nullable = false)
    private String name;
}
