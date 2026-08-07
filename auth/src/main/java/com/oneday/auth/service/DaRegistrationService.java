package com.oneday.auth.service;

import com.oneday.auth.dto.request.RegisterDaRequest;
import com.oneday.auth.dto.request.UpdateDaRequest;
import com.oneday.auth.dto.response.DaResponse;
import com.oneday.common.domain.Shift;

import java.util.List;
import java.util.UUID;

public interface DaRegistrationService {

    /** Creates the DELIVERY_ASSOCIATE user + HR profile. Returns a temp password when one was generated. */
    DaResponse register(RegisterDaRequest request, UUID actorId);

    List<DaResponse> list(String cityId, Shift shift, Boolean active);

    DaResponse update(UUID daId, UpdateDaRequest request);
}
