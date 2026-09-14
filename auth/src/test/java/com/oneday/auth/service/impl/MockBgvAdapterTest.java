package com.oneday.auth.service.impl;

import com.oneday.auth.config.BgvProperties;
import com.oneday.common.port.dto.bgv.BgvCheckStatus;
import com.oneday.common.port.dto.bgv.BgvCheckType;
import com.oneday.common.port.dto.bgv.BgvSubject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockBgvAdapterTest {

    private final MockBgvAdapter adapter = new MockBgvAdapter(new BgvProperties());

    private BgvCheckStatus verdict(BgvSubject s) {
        String ref = adapter.initiate(s, List.of(BgvCheckType.values())).vendorRef();
        return adapter.poll(ref, BgvCheckType.PAN).status();
    }

    @Test
    void cleanSubject_resolvesGreen() {
        assertThat(verdict(new BgvSubject("Riya Kumar", "ABCDE1234F", "DL01", "1111", "1998-05-01", "Delhi")))
                .isEqualTo(BgvCheckStatus.GREEN);
    }

    @Test
    void failMarker_resolvesRed() {
        assertThat(verdict(new BgvSubject("FAIL Person", "ABCDE1234F", null, null, null, null)))
                .isEqualTo(BgvCheckStatus.RED);
    }

    @Test
    void missingKeyFields_resolveInsufficient() {
        assertThat(verdict(new BgvSubject("Riya Kumar", null, null, null, null, null)))
                .isEqualTo(BgvCheckStatus.INSUFFICIENT);
    }

    @Test
    void isLive_reflectsProps() {
        var props = new BgvProperties();
        props.setLive(true);
        assertThat(new MockBgvAdapter(props).isLive()).isTrue();
        assertThat(adapter.isLive()).isFalse();
    }
}
