package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.port.EInvoicePort;
import com.oneday.common.port.dto.einvoice.EInvoiceResult;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Invoice;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.InvoiceResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.InvoiceRepository;
import com.oneday.orders.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    @Mock InvoiceRepository invoices;
    @Mock ShipmentRepository shipments;
    @Mock B2bAccountRepository accounts;
    @Mock EInvoicePort eInvoicePort;
    @InjectMocks InvoiceServiceImpl service;

    private final UUID caller = UUID.randomUUID();

    private static void zeroPaise(Invoice i) {
        i.setTaxableValuePaise(0L);
        i.setCgstPaise(0L);
        i.setSgstPaise(0L);
        i.setIgstPaise(0L);
        i.setTotalPaise(0L);
    }

    private Shipment shipment(CustomerType type) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef("REF");
        s.setBookedByUserId(caller);
        s.setSenderName("Arun");
        s.setCustomerType(type);
        s.setQuotedPricePaise(4000L);
        s.setTaxPaise(720L);
        s.setTotalPricePaise(4720L);
        return s;
    }

    @Test
    void getForShipment_notFound_is404() {
        when(shipments.findByShipmentRef("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getForShipment("X", caller))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getForShipment_wrongCaller_is404() {
        Shipment s = shipment(CustomerType.B2C);
        when(shipments.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.getForShipment("REF", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getForShipment_existingInvoice_returnsIt() {
        Shipment s = shipment(CustomerType.B2C);
        when(shipments.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        Invoice existing = new Invoice();
        zeroPaise(existing);
        existing.setInvoiceNumber("GS/2026-27/000001");
        existing.setIrn("IRN-X");
        when(invoices.findByShipmentId(s.getId())).thenReturn(Optional.of(existing));
        InvoiceResponse r = service.getForShipment("REF", caller);
        assertThat(r.invoiceNumber()).isEqualTo("GS/2026-27/000001");
        assertThat(r.eInvoiced()).isTrue();
    }

    @Test
    void getForShipment_issuesB2C_whenNoInvoice_notEInvoiced() {
        Shipment s = shipment(CustomerType.B2C);
        when(shipments.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        when(invoices.findByShipmentId(s.getId())).thenReturn(Optional.empty());
        when(invoices.nextInvoiceSequence()).thenReturn(1L);
        when(eInvoicePort.registerInvoice(any())).thenReturn(EInvoiceResult.notRegistered("pilot"));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));

        InvoiceResponse r = service.getForShipment("REF", caller);
        assertThat(r.invoiceNumber()).startsWith("GS/");
        assertThat(r.eInvoiced()).isFalse();
        assertThat(r.buyerName()).isEqualTo("Arun");
        assertThat(r.cgstPaise()).isEqualTo(360L); // 720/2
        assertThat(r.sgstPaise()).isEqualTo(360L);
    }

    @Test
    void getForShipment_issuesB2B_usesAccountAndEInvoiceRegistered() {
        Shipment s = shipment(CustomerType.B2B);
        s.setB2bAccountId(UUID.randomUUID());
        s.setQuotedPricePaise(null); // exercise nz() null branch
        when(shipments.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        when(invoices.findByShipmentId(s.getId())).thenReturn(Optional.empty());
        when(invoices.nextInvoiceSequence()).thenReturn(42L);
        B2bAccount acct = new B2bAccount();
        acct.setAccountName("Acme Corp");
        acct.setGstin("29ABCDE1234F1Z5");
        when(accounts.findById(s.getB2bAccountId())).thenReturn(Optional.of(acct));
        when(eInvoicePort.registerInvoice(any()))
                .thenReturn(new EInvoiceResult(true, "IRN-9", "QR==", "ok"));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));

        InvoiceResponse r = service.getForShipment("REF", caller);
        assertThat(r.buyerName()).isEqualTo("Acme Corp");
        assertThat(r.buyerGstin()).isEqualTo("29ABCDE1234F1Z5");
        assertThat(r.eInvoiced()).isTrue();
        assertThat(r.irn()).isEqualTo("IRN-9");
        assertThat(r.taxableValuePaise()).isZero(); // null quoted → 0
    }

    @Test
    void issue_raceLoses_returnsExistingWinner() {
        Shipment s = shipment(CustomerType.B2C);
        when(shipments.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        Invoice winner = new Invoice();
        zeroPaise(winner);
        winner.setInvoiceNumber("GS/2026-27/000007");
        when(invoices.findByShipmentId(s.getId()))
                .thenReturn(Optional.empty())        // getForShipment: none yet
                .thenReturn(Optional.of(winner));    // issue() catch: concurrent winner
        when(invoices.nextInvoiceSequence()).thenReturn(7L);
        when(eInvoicePort.registerInvoice(any())).thenReturn(EInvoiceResult.notRegistered("x"));
        when(invoices.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThat(service.getForShipment("REF", caller).invoiceNumber()).isEqualTo("GS/2026-27/000007");
    }

    @Test
    void issue_raceWithNoWinner_rethrows() {
        Shipment s = shipment(CustomerType.B2C);
        when(shipments.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        when(invoices.findByShipmentId(s.getId()))
                .thenReturn(Optional.empty()).thenReturn(Optional.empty());
        when(invoices.nextInvoiceSequence()).thenReturn(7L);
        when(eInvoicePort.registerInvoice(any())).thenReturn(EInvoiceResult.notRegistered("x"));
        when(invoices.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.getForShipment("REF", caller))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listMine_mapsRows() {
        Invoice i = new Invoice();
        zeroPaise(i);
        i.setInvoiceNumber("GS/2026-27/000001");
        i.setIssuedAt(Instant.now());
        when(invoices.findByBuyerUserIdOrderByIssuedAtDesc(caller)).thenReturn(List.of(i));
        List<InvoiceResponse> out = service.listMine(caller);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).eInvoiced()).isFalse();
    }
}
