package com.oneday.routing.config;

import com.oneday.routing.service.osrm.RoutingOsrmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Wires M6's OSRM client (M6-D-009). Exposed as a bean so {@code TravelMatrixService} injects it
 * and tests can supply a mock {@link RoutingOsrmClient} / {@link RestTemplate} instead of a live
 * OSRM. {@code @ConditionalOnMissingBean} lets a test context override either.
 */
@Configuration
public class OsrmConfig {

    @Bean
    @ConditionalOnMissingBean(name = "routingRestTemplate")
    RestTemplate routingRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    @ConditionalOnMissingBean
    RoutingOsrmClient routingOsrmClient(RoutingProperties properties, RestTemplate routingRestTemplate) {
        return new RoutingOsrmClient(properties.getOsrm().getBaseUrl(), routingRestTemplate);
    }
}
