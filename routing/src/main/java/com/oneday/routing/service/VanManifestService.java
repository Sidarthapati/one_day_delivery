package com.oneday.routing.service;

import com.oneday.routing.service.model.BindingResult;

import java.time.LocalDate;
import java.util.UUID;

// Manifest engine: binds real parcels to van loops against the locked plan (§11.2, §12).
public interface VanManifestService {

    // Last-mile: bind every parcel M7 has ready-for-delivery to its earliest deadline-feasible loop (§12.1).
    BindingResult bindDeliveries(UUID cityId, LocalDate date);

    // First-mile: bind every DA's accumulated parcels to their latest flight-feasible loop (§12.2).
    BindingResult bindCollections(UUID cityId, LocalDate date);
}
