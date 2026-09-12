package com.oneday.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A hub RTO work item (feature iii). Created when a return is initiated; tells the return hub (origin
 * hub for a same-city return, dest hub for a reverse-lane return) to physically turn the parcel around
 * — pull it from its open flight bag when {@code needsBagPull}, then dock-receive the return child so
 * it sorts back. Closed (status DONE) when the child leaves the hub (sorted) or a worker marks it done.
 */
@Entity
@Table(name = "rto_action")
@Getter
@Setter
@NoArgsConstructor
public class RtoAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "original_ref", length = 30, nullable = false, updatable = false)
    private String originalRef;

    @Column(name = "child_ref", length = 30, nullable = false, updatable = false)
    private String childRef;

    /** IATA code of the hub that must action the return (child's birth-hub city). */
    @Column(name = "return_hub_city", length = 10, nullable = false, updatable = false)
    private String returnHubCity;

    /** REVERSE_FROM_DEST | SAME_CITY_FROM_ORIGIN. */
    @Column(name = "lane", length = 32, nullable = false, updatable = false)
    private String lane;

    /** True when the parcel was pulled from an OPEN flight bag and must be fished out before sorting. */
    @Column(name = "needs_bag_pull", nullable = false)
    private boolean needsBagPull;

    /** OPEN | DONE. */
    @Column(name = "status", length = 16, nullable = false)
    private String status = "OPEN";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_by", length = 64)
    private String doneBy;
}
