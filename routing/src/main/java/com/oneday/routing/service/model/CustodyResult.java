package com.oneday.routing.service.model;

import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.common.port.ScanLedgerPort;

import java.util.UUID;

// Outcome of one custody scan. The scan is always written to the ledger (M8) regardless; this
// records what it did to the manifest item. UNKNOWN_PARCEL = scanned a parcel not on this van's
// manifest (mis-route → an EXTRA at stop reconcile); ILLEGAL_TRANSITION = out-of-order scan (C12).
public record CustodyResult(UUID parcelId, ScanLedgerPort.VanScanType type, Status status,
                            ManifestItemStatus itemStatus) {

    public enum Status { RECORDED, IDEMPOTENT, UNKNOWN_PARCEL, ILLEGAL_TRANSITION }

    public static CustodyResult recorded(UUID parcelId, ScanLedgerPort.VanScanType type, ManifestItemStatus to) {
        return new CustodyResult(parcelId, type, Status.RECORDED, to);
    }

    public static CustodyResult idempotent(UUID parcelId, ScanLedgerPort.VanScanType type, ManifestItemStatus at) {
        return new CustodyResult(parcelId, type, Status.IDEMPOTENT, at);
    }

    public static CustodyResult unknownParcel(UUID parcelId, ScanLedgerPort.VanScanType type) {
        return new CustodyResult(parcelId, type, Status.UNKNOWN_PARCEL, null);
    }

    public static CustodyResult illegal(UUID parcelId, ScanLedgerPort.VanScanType type, ManifestItemStatus at) {
        return new CustodyResult(parcelId, type, Status.ILLEGAL_TRANSITION, at);
    }

    public boolean accepted() {
        return status == Status.RECORDED || status == Status.IDEMPOTENT;
    }
}
