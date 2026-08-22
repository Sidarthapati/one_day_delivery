package com.oneday.exceptions.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The free-text → taxonomy mapping (M5 sends a loose reason string; we normalise it). */
class ExceptionReasonTest {

    @Test
    void mapsExactEnumNames() {
        assertThat(ExceptionReason.fromCode("CUSTOMER_REFUSED")).isEqualTo(ExceptionReason.CUSTOMER_REFUSED);
        assertThat(ExceptionReason.fromCode("da_no_show")).isEqualTo(ExceptionReason.DA_NO_SHOW);
    }

    @Test
    void mapsKnownAliases() {
        assertThat(ExceptionReason.fromCode("NO_ONE_HOME")).isEqualTo(ExceptionReason.CUSTOMER_UNAVAILABLE);
        assertThat(ExceptionReason.fromCode("wrong_address")).isEqualTo(ExceptionReason.ADDRESS_INCORRECT);
        assertThat(ExceptionReason.fromCode("REFUSED")).isEqualTo(ExceptionReason.CUSTOMER_REFUSED);
    }

    @Test
    void unknownOrBlankFallsBack() {
        assertThat(ExceptionReason.fromCode(null)).isEqualTo(ExceptionReason.UNKNOWN);
        assertThat(ExceptionReason.fromCode("   ")).isEqualTo(ExceptionReason.UNKNOWN);
        assertThat(ExceptionReason.fromCode("something-nobody-mapped")).isEqualTo(ExceptionReason.OTHER);
    }
}
