package com.oneday.auth;

import com.oneday.common.port.CityDirectoryPort;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

@SpringBootApplication
public class TestAuthApplication {

    /**
     * auth's e2e context can't reach grid (M3), which owns the real {@link CityDirectoryPort}. No e2e
     * test registers a DA, so a pass-through double (lowercase the code) is enough to satisfy wiring;
     * real code/alias/UUID normalisation lives in grid's adapter and is unit-tested there.
     */
    @Bean
    CityDirectoryPort cityDirectoryPort() {
        return v -> (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v.trim().toLowerCase());
    }
}
