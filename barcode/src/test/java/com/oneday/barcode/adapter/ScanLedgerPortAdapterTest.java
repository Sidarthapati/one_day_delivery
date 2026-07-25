package com.oneday.barcode.adapter;

import com.oneday.barcode.service.ScanCommand;
import com.oneday.barcode.service.ScanLedgerService;
import com.oneday.common.port.ScanLedgerPort.VanCustodyScan;
import com.oneday.common.port.ScanLedgerPort.VanScanType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The van-custody seam maps identity onto the ledger row and gives each (parcel, scan-type) a stable
 * synthetic client key so a driver-app retry dedups to one row.
 */
class ScanLedgerPortAdapterTest {

    private final ScanLedgerService ledger = mock(ScanLedgerService.class);
    private final ScanLedgerPortAdapter adapter = new ScanLedgerPortAdapter(ledger);

    @Test
    void mapsVanScanIdentityOntoLedgerRow() {
        UUID shipmentId = UUID.randomUUID(), van = UUID.randomUUID(),
             driver = UUID.randomUUID(), da = UUID.randomUUID();
        Instant at = Instant.parse("2026-07-12T06:47:00Z");

        adapter.recordVanScan(new VanCustodyScan(shipmentId, VanScanType.VAN_TO_DA, van, driver, da, at));

        ArgumentCaptor<ScanCommand> cmd = ArgumentCaptor.forClass(ScanCommand.class);
        verify(ledger).record(cmd.capture());
        ScanCommand c = cmd.getValue();
        assertThat(c.shipmentId()).isEqualTo(shipmentId);   // D-001: routing parcelId == shipment UUID
        assertThat(c.scanType()).isEqualTo("VAN_TO_DA");
        assertThat(c.locationType()).isEqualTo("VAN");
        assertThat(c.locationId()).isEqualTo(van);
        assertThat(c.actorId()).isEqualTo(driver);
        assertThat(c.counterpartyId()).isEqualTo(da);
        assertThat(c.scannedAt()).isEqualTo(at);
        assertThat(c.parcelId()).isNull();
        assertThat(c.clientScanId()).isNotNull();
    }

    @Test
    void retrySameParcelAndType_yieldsSameClientKey() {
        UUID shipmentId = UUID.randomUUID();
        VanCustodyScan first  = new VanCustodyScan(shipmentId, VanScanType.VAN_TO_DA,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        VanCustodyScan retry  = new VanCustodyScan(shipmentId, VanScanType.VAN_TO_DA,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        adapter.recordVanScan(first);
        adapter.recordVanScan(retry);

        ArgumentCaptor<ScanCommand> cmd = ArgumentCaptor.forClass(ScanCommand.class);
        verify(ledger, org.mockito.Mockito.times(2)).record(cmd.capture());
        // Same (shipmentId, scanType) → identical dedup key, so the engine collapses the replay.
        assertThat(cmd.getAllValues().get(0).clientScanId())
                .isEqualTo(cmd.getAllValues().get(1).clientScanId());
    }

    @Test
    void differentScanType_yieldsDifferentClientKey() {
        UUID shipmentId = UUID.randomUUID();
        adapter.recordVanScan(new VanCustodyScan(shipmentId, VanScanType.VAN_LOAD,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
        adapter.recordVanScan(new VanCustodyScan(shipmentId, VanScanType.VAN_UNLOAD,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()));

        ArgumentCaptor<ScanCommand> cmd = ArgumentCaptor.forClass(ScanCommand.class);
        verify(ledger, org.mockito.Mockito.times(2)).record(cmd.capture());
        assertThat(cmd.getAllValues().get(0).clientScanId())
                .isNotEqualTo(cmd.getAllValues().get(1).clientScanId());
    }
}
