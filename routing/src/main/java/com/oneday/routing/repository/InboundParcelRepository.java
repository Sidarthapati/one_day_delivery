package com.oneday.routing.repository;

import com.oneday.routing.domain.InboundKind;
import com.oneday.routing.domain.InboundParcel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InboundParcelRepository extends JpaRepository<InboundParcel, UUID> {

    List<InboundParcel> findByCityIdAndValidDateAndKind(UUID cityId, LocalDate validDate, InboundKind kind);

    List<InboundParcel> findByDaIdAndValidDateAndKind(UUID daId, LocalDate validDate, InboundKind kind);

    boolean existsByKindAndParcelId(InboundKind kind, UUID parcelId);
}
