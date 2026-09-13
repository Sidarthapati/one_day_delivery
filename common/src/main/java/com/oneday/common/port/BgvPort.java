package com.oneday.common.port;

import com.oneday.common.port.dto.bgv.BgvCheckResult;
import com.oneday.common.port.dto.bgv.BgvCheckType;
import com.oneday.common.port.dto.bgv.BgvInitiateResult;
import com.oneday.common.port.dto.bgv.BgvSubject;

import java.util.List;

/**
 * Background-verification vendor seam. Mirrors {@link KycPort}: a single adapter switches between a
 * deterministic mock ({@code bgv.live=false}, the default) and a real vendor. The contract is async and
 * per-check — {@code initiate} opens a case, then each check is polled to a Green/Amber/Red/Insufficient
 * verdict — so it fits both instant digital checks and field/TAT checks regardless of which vendor is
 * chosen later. The real adapter (SpringVerify/Surepass/OnGrid/IDfy) is a drop-in that keeps this shape.
 */
public interface BgvPort {

    /** Open a verification case for the subject across the requested checks. Returns a vendor ref. */
    BgvInitiateResult initiate(BgvSubject subject, List<BgvCheckType> checks);

    /** Poll one check's current status/verdict. */
    BgvCheckResult poll(String vendorRef, BgvCheckType check);

    /** True when wired to a real vendor (mock otherwise). */
    boolean isLive();
}
