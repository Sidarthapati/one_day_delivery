package com.oneday.orders.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** All cases use IP literals (no DNS) so the test is network-free. */
class SsrfGuardTest {

    @Test
    void blocksMetadataPrivateAndLoopback() {
        assertThat(SsrfGuard.isSafe("http://169.254.169.254/latest/meta-data/")).isFalse(); // cloud IMDS
        assertThat(SsrfGuard.isSafe("https://127.0.0.1/")).isFalse();                        // loopback
        assertThat(SsrfGuard.isSafe("https://10.0.0.5/hook")).isFalse();                     // RFC1918
        assertThat(SsrfGuard.isSafe("https://192.168.1.10/")).isFalse();
        assertThat(SsrfGuard.isSafe("https://172.16.0.1/")).isFalse();
        assertThat(SsrfGuard.isSafe("https://100.64.0.1/")).isFalse();                       // CGNAT
    }

    @Test
    void blocksNonHttpSchemesAndMalformed() {
        assertThat(SsrfGuard.isSafe("ftp://198.51.100.7/")).isFalse();
        assertThat(SsrfGuard.isSafe("file:///etc/passwd")).isFalse();
        assertThat(SsrfGuard.isSafe("gopher://198.51.100.7")).isFalse();
        assertThat(SsrfGuard.isSafe("not a url")).isFalse();
        assertThat(SsrfGuard.isSafe("")).isFalse();
        assertThat(SsrfGuard.isSafe(null)).isFalse();
    }

    @Test
    void allowsPublicHttpAndHttps() {
        assertThat(SsrfGuard.isSafe("https://1.1.1.1/webhook")).isTrue();
        assertThat(SsrfGuard.isSafe("http://8.8.8.8/hook")).isTrue();
    }
}
