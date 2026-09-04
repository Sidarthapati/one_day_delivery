package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.IpReputationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * CIDR-based datacenter/VPN detection. Matches the client IP against a config-driven list of IPv4
 * ranges (a small starter set of well-known cloud/VPN ASNs, extended via {@code dispatch.ip-reputation
 * .datacenter-cidrs}). IPv6 and unparseable inputs are treated as clean — this is a soft signal, so
 * a miss is safe. Bad CIDR entries are skipped, not fatal.
 */
@Service
class IpReputationServiceImpl implements IpReputationService {

    private final DispatchProperties props;
    private volatile List<Cidr> parsed;

    IpReputationServiceImpl(DispatchProperties props) {
        this.props = props;
    }

    @Override
    public IpReputation evaluate(String clientIp) {
        DispatchProperties.IpReputation cfg = props.getIpReputation();
        if (!cfg.isEnabled() || clientIp == null || clientIp.isBlank()) {
            return IpReputation.clean();
        }
        long ip = parseIpv4(clientIp.trim());
        if (ip < 0) {
            return IpReputation.clean(); // IPv6 / unparseable — don't guess
        }
        for (Cidr c : cidrs(cfg)) {
            if (c.contains(ip)) {
                return new IpReputation(true, cfg.getRiskBump(), "datacenter/VPN IP");
            }
        }
        return IpReputation.clean();
    }

    private List<Cidr> cidrs(DispatchProperties.IpReputation cfg) {
        List<Cidr> local = parsed;
        if (local == null) {
            local = new ArrayList<>();
            for (String raw : cfg.getDatacenterCidrs()) {
                Cidr c = Cidr.parse(raw);
                if (c != null) {
                    local.add(c);
                }
            }
            parsed = local;
        }
        return local;
    }

    /** Returns the 32-bit IPv4 as an unsigned long, or -1 if not a valid dotted-quad. */
    static long parseIpv4(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long v = 0;
        for (String p : parts) {
            try {
                int octet = Integer.parseInt(p);
                if (octet < 0 || octet > 255) {
                    return -1;
                }
                v = (v << 8) | octet;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return v;
    }

    /** An IPv4 CIDR block. */
    record Cidr(long network, long mask) {
        static Cidr parse(String raw) {
            if (raw == null) {
                return null;
            }
            String[] parts = raw.trim().split("/");
            if (parts.length != 2) {
                return null;
            }
            long base = parseIpv4(parts[0]);
            if (base < 0) {
                return null;
            }
            int bits;
            try {
                bits = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (bits < 0 || bits > 32) {
                return null;
            }
            long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return new Cidr(base & mask, mask);
        }

        boolean contains(long ip) {
            return (ip & mask) == network;
        }
    }
}
