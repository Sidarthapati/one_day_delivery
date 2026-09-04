package com.oneday.dispatch.service;

/**
 * A soft location-trust signal derived from the request's client IP (anti-abuse Phase 5). A DA pinging
 * from a datacenter / VPN / proxy range isn't on a phone in the field — suspicious, but not proof of
 * cheating (carrier-grade NAT, corporate VPNs), so per the hybrid posture this only RAISES the risk
 * score for ops review; it never hard-blocks. Backed by a config-driven CIDR list so a richer
 * reputation feed can swap in later without touching callers.
 */
public interface IpReputationService {

    /** Evaluate a client IP (may be null/blank → clean). */
    IpReputation evaluate(String clientIp);

    /**
     * @param datacenter true if the IP falls in a known datacenter/VPN/proxy range
     * @param riskBump   points to add to the ping's risk score (0 when clean)
     * @param reason     short human label, or null when clean
     */
    record IpReputation(boolean datacenter, int riskBump, String reason) {
        public static IpReputation clean() {
            return new IpReputation(false, 0, null);
        }
    }
}
