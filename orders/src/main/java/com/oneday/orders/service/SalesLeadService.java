package com.oneday.orders.service;

import com.oneday.orders.dto.CreateSalesLeadRequest;
import com.oneday.orders.dto.SalesLeadResponse;

import java.util.List;
import java.util.UUID;

public interface SalesLeadService {

    SalesLeadResponse create(CreateSalesLeadRequest request);

    List<SalesLeadResponse> list(String statusOrNull);

    SalesLeadResponse updateStatus(UUID id, String status);
}
