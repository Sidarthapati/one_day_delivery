package com.oneday.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

// Exclude com.oneday.auth.local.AuthLocalApplication: it's a second @SpringBootApplication
// (a standalone M1 launcher) that the com.oneday scan would otherwise pick up, which breaks
// the assembled app's boot.
@SpringBootApplication
@ComponentScan(
        basePackages = "com.oneday",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.oneday\\.auth\\.local\\..*"))
@EnableJpaRepositories(basePackages = "com.oneday")
@EntityScan(basePackages = "com.oneday")
@EnableScheduling
public class OneDayDeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(OneDayDeliveryApplication.class, args);
    }
}
