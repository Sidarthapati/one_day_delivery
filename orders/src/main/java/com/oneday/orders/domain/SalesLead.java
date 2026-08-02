package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A prospect who submitted the public "Talk to sales" form. */
@Entity
@Table(name = "sales_lead")
@Getter
@Setter
@NoArgsConstructor
public class SalesLead extends MutableBaseEntity {

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "company", length = 200)
    private String company;

    @Column(name = "email", length = 254, nullable = false)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "monthly_volume", length = 20)
    private String monthlyVolume;

    @Column(name = "message", length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private SalesLeadStatus status = SalesLeadStatus.NEW;
}
