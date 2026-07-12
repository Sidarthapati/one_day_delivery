package com.oneday.barcode.api;

import com.oneday.barcode.service.LabelService;
import com.oneday.barcode.service.ScanCommand;
import com.oneday.barcode.service.ScanLedgerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * The generic scan door only accepts lifecycle {@code ScanEventType}s — van custody scans and
 * {@code LABEL_GENERATED} (its own door) are rejected before the ledger is touched.
 */
class ScanControllerTest {

    private final ScanLedgerService ledger = mock(ScanLedgerService.class);
    private final ScanController controller = new ScanController(mock(LabelService.class), ledger);

    private ScanRequest req(String scanType) {
        return new ScanRequest(UUID.randomUUID(), scanType, "HUB",
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, Instant.now(), UUID.randomUUID());
    }

    @Test
    void lifecycleScan_isRecorded() {
        controller.scan(req("HUB_ORIGIN_IN"));

        ArgumentCaptor<ScanCommand> cmd = ArgumentCaptor.forClass(ScanCommand.class);
        verify(ledger).record(cmd.capture());
        assertThat(cmd.getValue().scanType()).isEqualTo("HUB_ORIGIN_IN");
    }

    @Test
    void scannedAt_defaultsToNow_whenOmitted() {
        controller.scan(new ScanRequest(UUID.randomUUID(), "HUB_DEST_IN", "HUB",
                null, null, null, null, null, null, null));

        ArgumentCaptor<ScanCommand> cmd = ArgumentCaptor.forClass(ScanCommand.class);
        verify(ledger).record(cmd.capture());
        assertThat(cmd.getValue().scannedAt()).isNotNull();
    }

    @Test
    void vanScanType_isRejected() {
        assertThatThrownBy(() -> controller.scan(req("VAN_LOAD")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ledger);
    }

    @Test
    void labelGenerated_isRejected_hasItsOwnDoor() {
        assertThatThrownBy(() -> controller.scan(req("LABEL_GENERATED")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ledger);
    }
}
