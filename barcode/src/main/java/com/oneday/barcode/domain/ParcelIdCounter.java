package com.oneday.barcode.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-hub, per-day counter backing the seq6 suffix of the parcel barcode (design §3.2).
 * {@code nextSeq} is the next value to hand out; PR2 increments it under a row lock
 * (mirrors {@code orders.ShipmentRefCounter}).
 */
@Entity
@Table(name = "parcel_id_counter")
@Getter
@Setter
@NoArgsConstructor
public class ParcelIdCounter {

    @EmbeddedId
    private ParcelIdCounterId id;

    @Column(name = "next_seq", nullable = false)
    private Integer nextSeq;
}
