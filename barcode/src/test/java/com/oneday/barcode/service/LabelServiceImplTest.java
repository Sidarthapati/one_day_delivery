package com.oneday.barcode.service;

import com.oneday.barcode.domain.ScanLedgerEntry;
import com.oneday.barcode.repository.ScanLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabelServiceImplTest {

    @Mock ScanLedgerRepository repository;
    @Mock ParcelIdGenerator generator;
    @Mock ScanLedgerService ledger;
    @InjectMocks LabelServiceImpl service;

    final UUID shipment = UUID.randomUUID();

    @Test
    void returnsExistingBarcode_whenAlreadyLabelled_withoutMintingOrBumping() {
        ScanLedgerEntry existing = ScanLedgerEntry.builder().parcelId("1DD-DEL-260711-000007").build();
        when(repository.findFirstByShipmentIdAndScanType(shipment, "LABEL_GENERATED"))
                .thenReturn(Optional.of(existing));

        String barcode = service.generateLabel(shipment, "DEL", UUID.randomUUID(), null);

        assertThat(barcode).isEqualTo("1DD-DEL-260711-000007");
        verifyNoInteractions(generator, ledger); // idempotent: no new mint, no counter bump
    }

    @Test
    void mintsAndRecords_onFirstLabel_normalisingDestCity() {
        UUID actor = UUID.randomUUID();
        when(repository.findFirstByShipmentIdAndScanType(shipment, "LABEL_GENERATED"))
                .thenReturn(Optional.empty());
        when(generator.next("DEL")).thenReturn("1DD-DEL-260711-000042");

        String barcode = service.generateLabel(shipment, " del ", actor, null);

        assertThat(barcode).isEqualTo("1DD-DEL-260711-000042");
        verify(generator).next("DEL"); // trimmed + uppercased

        ArgumentCaptor<ScanCommand> cmd = ArgumentCaptor.forClass(ScanCommand.class);
        verify(ledger).record(cmd.capture());
        assertThat(cmd.getValue().scanType()).isEqualTo("LABEL_GENERATED");
        assertThat(cmd.getValue().parcelId()).isEqualTo("1DD-DEL-260711-000042");
        assertThat(cmd.getValue().actorId()).isEqualTo(actor);
        assertThat(cmd.getValue().locationType()).isEqualTo("DA");
    }
}
