package com.oneday.shuttle.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HubCodeTest {

    @Test
    void normalisesGridCityNamesToIataHubCodes() {
        assertThat(HubCode.of("delhi")).isEqualTo("DEL");
        assertThat(HubCode.of("Hyderabad")).isEqualTo("HYD");
        assertThat(HubCode.of("bengaluru")).isEqualTo("BLR");
    }

    @Test
    void passesThroughIataCodesUnchanged() {
        assertThat(HubCode.of("DEL")).isEqualTo("DEL");
        assertThat(HubCode.of("hyd")).isEqualTo("HYD");
    }

    @Test
    void unknownFallsBackToUpperCase() {
        assertThat(HubCode.of("goa")).isEqualTo("GOA");
        assertThat(HubCode.of(null)).isNull();
    }
}
