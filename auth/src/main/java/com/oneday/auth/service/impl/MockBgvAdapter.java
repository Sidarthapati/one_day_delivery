package com.oneday.auth.service.impl;

import com.oneday.auth.config.BgvProperties;
import com.oneday.common.port.BgvPort;
import com.oneday.common.port.dto.bgv.BgvCheckResult;
import com.oneday.common.port.dto.bgv.BgvCheckStatus;
import com.oneday.common.port.dto.bgv.BgvCheckType;
import com.oneday.common.port.dto.bgv.BgvInitiateResult;
import com.oneday.common.port.dto.bgv.BgvSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The only {@link BgvPort} bean — a deterministic mock (no real vendor is wired yet; the vendor decision
 * is deferred). Mirrors {@link SandboxKycAdapter}: it branches on {@code bgv.live}. The outcome is encoded
 * into the {@code vendorRef} at {@code initiate} so {@code poll} is stateless yet deterministic:
 * <ul>
 *   <li>any subject field containing "FAIL" → every check resolves <b>RED</b>;</li>
 *   <li>missing name or PAN → <b>INSUFFICIENT</b>;</li>
 *   <li>otherwise → <b>GREEN</b>.</li>
 * </ul>
 * The mock resolves on the first poll (the case row still starts IN_PROGRESS, so the async lifecycle is
 * exercised). A real adapter (SpringVerify/Surepass/OnGrid/IDfy) replaces this class behind the same port.
 */
@Component
class MockBgvAdapter implements BgvPort {

    private static final Logger LOG = LoggerFactory.getLogger(MockBgvAdapter.class);

    private final BgvProperties props;

    MockBgvAdapter(BgvProperties props) {
        this.props = props;
    }

    @Override
    public BgvInitiateResult initiate(BgvSubject subject, List<BgvCheckType> checks) {
        if (props.isLive()) {
            // No real vendor is integrated yet. Fail safe: open a ref whose checks resolve INSUFFICIENT.
            LOG.warn("bgv.live=true but no real BGV vendor is wired — falling back to INSUFFICIENT verdicts");
            return new BgvInitiateResult("live-unconfigured-" + UUID.randomUUID());
        }
        char code = outcomeCode(subject);
        return new BgvInitiateResult("mock-" + code + "-" + UUID.randomUUID());
    }

    @Override
    public BgvCheckResult poll(String vendorRef, BgvCheckType check) {
        if (vendorRef != null && vendorRef.startsWith("live-unconfigured")) {
            return new BgvCheckResult(BgvCheckStatus.INSUFFICIENT, "Live BGV vendor not configured");
        }
        char code = vendorRef != null && vendorRef.length() > 5 ? vendorRef.charAt(5) : 'G';
        return switch (code) {
            case 'R' -> new BgvCheckResult(BgvCheckStatus.RED, "Adverse record found (mock)");
            case 'I' -> new BgvCheckResult(BgvCheckStatus.INSUFFICIENT, "Insufficient information (mock)");
            default -> new BgvCheckResult(BgvCheckStatus.GREEN, "Clear (mock)");
        };
    }

    @Override
    public boolean isLive() {
        return props.isLive();
    }

    /** 'R' = adverse (any "FAIL" marker), 'I' = insufficient (missing key fields), 'G' = clear. */
    private static char outcomeCode(BgvSubject s) {
        if (containsFail(s.fullName()) || containsFail(s.pan()) || containsFail(s.drivingLicense())
                || containsFail(s.aadhaar()) || containsFail(s.address())) {
            return 'R';
        }
        if (isBlank(s.fullName()) || isBlank(s.pan())) {
            return 'I';
        }
        return 'G';
    }

    private static boolean containsFail(String v) {
        return v != null && v.toUpperCase().contains("FAIL");
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
