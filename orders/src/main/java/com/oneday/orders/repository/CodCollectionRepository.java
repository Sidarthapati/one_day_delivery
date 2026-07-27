package com.oneday.orders.repository;

import com.oneday.orders.domain.CodCollection;
import com.oneday.orders.domain.CodCollectionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodCollectionRepository extends JpaRepository<CodCollection, UUID> {

    Optional<CodCollection> findByShipmentId(UUID shipmentId);

    List<CodCollection> findByB2bAccountIdOrderByCreatedAtDesc(UUID b2bAccountId);

    List<CodCollection> findByB2bAccountIdAndStateOrderByCreatedAtDesc(UUID b2bAccountId, CodCollectionState state);

    List<CodCollection> findByRemittanceIdOrderByCreatedAtDesc(UUID remittanceId);

    int countByB2bAccountIdAndState(UUID b2bAccountId, CodCollectionState state);

    /** COLLECTED but not yet assigned to a remittance — the amount available to pay out. */
    @Query("SELECT c FROM CodCollection c WHERE c.b2bAccountId = :accountId "
            + "AND c.state = com.oneday.orders.domain.CodCollectionState.COLLECTED AND c.remittanceId IS NULL")
    List<CodCollection> findRemittable(@Param("accountId") UUID accountId);

    /** Σ amount for one account in a given state (0 when none). */
    @Query("SELECT COALESCE(SUM(c.amountPaise), 0) FROM CodCollection c "
            + "WHERE c.b2bAccountId = :accountId AND c.state = :state")
    long sumByAccountAndState(@Param("accountId") UUID accountId, @Param("state") CodCollectionState state);

    /** Σ COLLECTED-and-unremitted for one account — the payout-available balance. */
    @Query("SELECT COALESCE(SUM(c.amountPaise), 0) FROM CodCollection c WHERE c.b2bAccountId = :accountId "
            + "AND c.state = com.oneday.orders.domain.CodCollectionState.COLLECTED AND c.remittanceId IS NULL")
    long sumRemittable(@Param("accountId") UUID accountId);

    /** Distinct accounts that currently have a payout-available balance (admin worklist). */
    @Query("SELECT DISTINCT c.b2bAccountId FROM CodCollection c "
            + "WHERE c.state = com.oneday.orders.domain.CodCollectionState.COLLECTED AND c.remittanceId IS NULL")
    List<UUID> findAccountsWithRemittableBalance();
}
