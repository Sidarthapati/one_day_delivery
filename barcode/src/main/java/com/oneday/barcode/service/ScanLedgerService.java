package com.oneday.barcode.service;

import com.oneday.barcode.domain.ScanLedgerEntry;

/**
 * The append-only write engine (M8 §4.3) — built once, reused by every scan entry door: the label
 * path (PR2), the REST lifecycle scans (PR3), and the van custody port (PR4). Idempotent on
 * {@code clientScanId}; emits a {@code ScanEvent} after commit for broadcastable scan types.
 */
public interface ScanLedgerService {

    /** Append one immutable scan row (idempotent replay on {@code clientScanId}). */
    ScanLedgerEntry record(ScanCommand cmd);
}
