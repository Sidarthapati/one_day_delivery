package com.oneday.dispatch;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap so @DataJpaTest has a @SpringBootConfiguration to find in this library module
 * (dispatch has no main application class). Component/entity/repository scanning is rooted at
 * com.oneday.dispatch, so only M5 entities and repositories are picked up (grid/orders live in other
 * packages and are not scanned).
 */
@SpringBootApplication
public class DispatchTestApplication {
}
