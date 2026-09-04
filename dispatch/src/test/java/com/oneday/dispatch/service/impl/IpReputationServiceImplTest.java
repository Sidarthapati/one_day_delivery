package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.IpReputationService.IpReputation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CIDR matching for the soft VPN/datacenter signal: a listed range flags (with a bump); anything else, IPv6, or junk is clean. */
class IpReputationServiceImplTest {

    private final DispatchProperties props = new DispatchProperties();
    private final IpReputationServiceImpl service = new IpReputationServiceImpl(props);

    @Test
    void datacenterIp_flagsWithBump() {
        // 34.0.0.0/8 (Google Cloud) is in the starter list.
        IpReputation r = service.evaluate("34.120.5.9");
        assertThat(r.datacenter()).isTrue();
        assertThat(r.riskBump()).isEqualTo(props.getIpReputation().getRiskBump());
    }

    @Test
    void residentialIp_isClean() {
        IpReputation r = service.evaluate("49.36.100.20"); // a typical Indian residential range, not listed
        assertThat(r.datacenter()).isFalse();
        assertThat(r.riskBump()).isZero();
    }

    @Test
    void nullOrIpv6OrJunk_isClean() {
        assertThat(service.evaluate(null).datacenter()).isFalse();
        assertThat(service.evaluate("2001:db8::1").datacenter()).isFalse();
        assertThat(service.evaluate("not-an-ip").datacenter()).isFalse();
    }

    @Test
    void disabled_isAlwaysClean() {
        props.getIpReputation().setEnabled(false);
        assertThat(service.evaluate("34.120.5.9").datacenter()).isFalse();
    }

    @Test
    void customCidr_matches() {
        props.getIpReputation().setDatacenterCidrs(List.of("10.10.0.0/16"));
        IpReputationServiceImpl svc = new IpReputationServiceImpl(props);
        assertThat(svc.evaluate("10.10.5.5").datacenter()).isTrue();
        assertThat(svc.evaluate("10.11.5.5").datacenter()).isFalse();
    }
}
