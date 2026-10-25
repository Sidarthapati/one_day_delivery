package com.oneday.orders.service.impl;

import com.oneday.orders.domain.SalesLead;
import com.oneday.orders.domain.SalesLeadStatus;
import com.oneday.orders.dto.CreateSalesLeadRequest;
import com.oneday.orders.dto.SalesLeadResponse;
import com.oneday.orders.repository.SalesLeadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesLeadServiceImplTest {

    @Mock SalesLeadRepository leads;
    @InjectMocks SalesLeadServiceImpl service;

    private static CreateSalesLeadRequest req(String name, String company, String email,
                                              String phone, String volume, String message) {
        CreateSalesLeadRequest r = new CreateSalesLeadRequest();
        ReflectionTestUtils.setField(r, "name", name);
        ReflectionTestUtils.setField(r, "company", company);
        ReflectionTestUtils.setField(r, "email", email);
        ReflectionTestUtils.setField(r, "phone", phone);
        ReflectionTestUtils.setField(r, "monthlyVolume", volume);
        ReflectionTestUtils.setField(r, "message", message);
        return r;
    }

    @Test
    void create_trimsAndBlankToNulls_andSetsNew() {
        // name trimmed; company null → null; phone blank → null; volume/message trimmed to value;
        // email non-null (safe() replace branch) + company null (safe() null branch).
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        SalesLeadResponse r = service.create(req("  Arun  ", null, "a@b.com\n", "  ", "100", "  hi "));
        assertThat(r.name()).isEqualTo("Arun");
        assertThat(r.company()).isNull();
        assertThat(r.phone()).isNull();
        assertThat(r.monthlyVolume()).isEqualTo("100");
        assertThat(r.message()).isEqualTo("hi");
        assertThat(r.status()).isEqualTo("NEW");
    }

    @Test
    void list_noFilter_usesFindAll() {
        when(leads.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(lead(SalesLeadStatus.NEW)));
        assertThat(service.list(null)).hasSize(1);   // null → findAll
        assertThat(service.list("  ")).hasSize(1);   // blank → findAll (same branch)
    }

    @Test
    void list_withStatus_parsesAndFilters() {
        when(leads.findByStatusOrderByCreatedAtDesc(SalesLeadStatus.WON))
                .thenReturn(List.of(lead(SalesLeadStatus.WON)));
        assertThat(service.list("won")).hasSize(1);
    }

    @Test
    void list_badStatus_is400() {
        assertThatThrownBy(() -> service.list("nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown lead status");
    }

    @Test
    void updateStatus_found_saves() {
        SalesLead l = lead(SalesLeadStatus.NEW);
        UUID id = UUID.randomUUID();
        when(leads.findById(id)).thenReturn(java.util.Optional.of(l));
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.updateStatus(id, "CONTACTED").status()).isEqualTo("CONTACTED");
    }

    @Test
    void updateStatus_notFound_is404() {
        UUID id = UUID.randomUUID();
        when(leads.findById(id)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.updateStatus(id, "WON"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Lead not found");
    }

    private static SalesLead lead(SalesLeadStatus status) {
        SalesLead l = new SalesLead();
        l.setName("N");
        l.setEmail("e@x.com");
        l.setStatus(status);
        return l;
    }
}
