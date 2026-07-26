package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.port.EInvoicePort;
import com.oneday.common.port.dto.einvoice.EInvoiceRequest;
import com.oneday.common.port.dto.einvoice.EInvoiceResult;
import com.oneday.orders.domain.Invoice;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.InvoiceResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.InvoiceRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.InvoiceService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
class InvoiceServiceImpl implements InvoiceService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SAC_COURIER = "996812";

    private final InvoiceRepository invoices;
    private final ShipmentRepository shipments;
    private final B2bAccountRepository accounts;
    private final EInvoicePort eInvoicePort;

    InvoiceServiceImpl(InvoiceRepository invoices, ShipmentRepository shipments,
                       B2bAccountRepository accounts, EInvoicePort eInvoicePort) {
        this.invoices = invoices;
        this.shipments = shipments;
        this.accounts = accounts;
        this.eInvoicePort = eInvoicePort;
    }

    @Override
    @Transactional
    public InvoiceResponse getForShipment(String shipmentRef, UUID callerUserId) {
        Shipment s = shipments.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
        if (!callerUserId.equals(s.getBookedByUserId())) {
            // Don't leak existence of others' shipments.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found");
        }
        Invoice invoice = invoices.findByShipmentId(s.getId()).orElseGet(() -> issue(s));
        return toResponse(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceResponse> listMine(UUID callerUserId) {
        return invoices.findByBuyerUserIdOrderByIssuedAtDesc(callerUserId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Generate + persist an invoice from the shipment's stored pricing. */
    private Invoice issue(Shipment s) {
        long taxable = nz(s.getQuotedPricePaise());
        long tax = nz(s.getTaxPaise());
        long total = nz(s.getTotalPricePaise());

        // TODO(tax): split assumes intra-state supply (CGST+SGST). Wire real place-of-supply
        // (supplier state vs recipient state → IGST when inter-state) once GST registration lands.
        long cgst = tax / 2;
        long sgst = tax - cgst;
        long igst = 0L;

        String buyerName = s.getSenderName();
        String buyerGstin = null;
        if (s.getCustomerType() == CustomerType.B2B && s.getB2bAccountId() != null) {
            var acct = accounts.findById(s.getB2bAccountId()).orElse(null);
            if (acct != null) {
                buyerName = acct.getAccountName();
                buyerGstin = acct.getGstin();
            }
        }

        String number = "GS/" + financialYear() + "/" + String.format("%06d", invoices.nextInvoiceSequence());

        Invoice inv = new Invoice();
        inv.setShipmentId(s.getId());
        inv.setShipmentRef(s.getShipmentRef());
        inv.setInvoiceNumber(number);
        inv.setCustomerType(s.getCustomerType().name());
        inv.setBuyerUserId(s.getBookedByUserId());
        inv.setBuyerName(buyerName);
        inv.setBuyerGstin(buyerGstin);
        inv.setSacCode(SAC_COURIER);
        inv.setTaxableValuePaise(taxable);
        inv.setCgstPaise(cgst);
        inv.setSgstPaise(sgst);
        inv.setIgstPaise(igst);
        inv.setTotalPaise(total);
        inv.setIssuedAt(Instant.now());
        inv.setStatus("ISSUED");

        // Government e-invoicing (no-op in the pilot) — fills IRN/QR when a real GSP is wired.
        EInvoiceResult e = eInvoicePort.registerInvoice(new EInvoiceRequest(
                number, buyerGstin, SAC_COURIER, taxable, cgst, sgst, igst, total));
        if (e.registered()) {
            inv.setIrn(e.irn());
            inv.setIrnQr(e.signedQr());
        }

        try {
            return invoices.save(inv);
        } catch (DataIntegrityViolationException race) {
            // Concurrent first-fetch already created it — return the winner.
            return invoices.findByShipmentId(s.getId())
                    .orElseThrow(() -> race);
        }
    }

    private InvoiceResponse toResponse(Invoice i) {
        return new InvoiceResponse(
                i.getInvoiceNumber(), i.getShipmentRef(), i.getCustomerType(),
                i.getBuyerName(), i.getBuyerGstin(), i.getSacCode(),
                i.getTaxableValuePaise(), i.getCgstPaise(), i.getSgstPaise(), i.getIgstPaise(),
                i.getTotalPaise(), i.getIrn() != null, i.getIrn(), i.getIssuedAt());
    }

    private static String financialYear() {
        LocalDate d = LocalDate.ofInstant(Instant.now(), IST);
        int startYear = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return startYear + "-" + String.format("%02d", (startYear + 1) % 100);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
