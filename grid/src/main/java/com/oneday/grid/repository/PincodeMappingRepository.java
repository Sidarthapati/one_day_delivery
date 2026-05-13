package com.oneday.grid.repository;

import com.oneday.grid.domain.PincodeMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PincodeMappingRepository extends JpaRepository<PincodeMapping, UUID> {

    Optional<PincodeMapping> findByCityIdAndPincode(UUID cityId, String pincode);
}
