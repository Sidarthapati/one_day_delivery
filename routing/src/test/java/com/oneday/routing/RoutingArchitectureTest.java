package com.oneday.routing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the cross-module rule (CLAUDE.md, PR #2 verify): routing may import only grid's public
 * service interface + its response DTOs — never {@code grid.domain} or {@code grid.repository}.
 * A lightweight source grep (no ArchUnit dependency).
 */
class RoutingArchitectureTest {

    private static final List<String> FORBIDDEN = List.of(
            "import com.oneday.grid.domain",
            "import com.oneday.grid.repository",
            "import com.oneday.grid.service.impl"
    );

    @Test
    void noGridInternalImports() throws IOException {
        Path srcRoot = Path.of("src/main/java");
        assertThat(Files.exists(srcRoot)).as("source root resolvable from module dir").isTrue();

        try (Stream<Path> files = Files.walk(srcRoot)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String content;
                try {
                    content = Files.readString(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                for (String forbidden : FORBIDDEN) {
                    assertThat(content)
                            .as("%s must not import a grid internal package (%s)", p, forbidden)
                            .doesNotContain(forbidden);
                }
            });
        }
    }
}
