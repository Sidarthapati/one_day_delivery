package com.oneday.orders.service.impl;

import com.oneday.orders.domain.SalesLead;
import com.oneday.orders.domain.SalesLeadStatus;
import com.oneday.orders.dto.CreateSalesLeadRequest;
import com.oneday.orders.dto.SalesLeadResponse;
import com.oneday.orders.repository.SalesLeadRepository;
import com.oneday.orders.service.SalesLeadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
class SalesLeadServiceImpl implements SalesLeadService {

    private static final Logger log = LoggerFactory.getLogger(SalesLeadServiceImpl.class);

    private final SalesLeadRepository leads;

    SalesLeadServiceImpl(SalesLeadRepository leads) {
        this.leads = leads;
    }

    @Override
    @Transactional
    public SalesLeadResponse create(CreateSalesLeadRequest r) {
        SalesLead lead = new SalesLead();
        lead.setName(r.getName().trim());
        lead.setCompany(blankToNull(r.getCompany()));
        lead.setEmail(r.getEmail().trim());
        lead.setPhone(blankToNull(r.getPhone()));
        lead.setMonthlyVolume(blankToNull(r.getMonthlyVolume()));
        lead.setMessage(blankToNull(r.getMessage()));
        lead.setStatus(SalesLeadStatus.NEW);
        lead = leads.save(lead);
        log.info("New sales lead {} from {} ({})", lead.getId(), safe(lead.getEmail()), safe(lead.getCompany()));
        return SalesLeadResponse.from(lead);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesLeadResponse> list(String statusOrNull) {
        List<SalesLead> rows = (statusOrNull == null || statusOrNull.isBlank())
                ? leads.findAllByOrderByCreatedAtDesc()
                : leads.findByStatusOrderByCreatedAtDesc(parse(statusOrNull));
        return rows.stream().map(SalesLeadResponse::from).toList();
    }

    @Override
    @Transactional
    public SalesLeadResponse updateStatus(UUID id, String status) {
        SalesLead lead = leads.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
        lead.setStatus(parse(status));
        return SalesLeadResponse.from(leads.save(lead));
    }

    private static SalesLeadStatus parse(String s) {
        try {
            return SalesLeadStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown lead status: " + s);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // Strip CR/LF/tab from caller-supplied strings before logging (prevents log forging).
    private static String safe(String s) {
        return s == null ? null : s.replaceAll("[\\r\\n\\t]", "_");
    }
}
