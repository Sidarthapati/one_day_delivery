package com.oneday.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.oneday")
@EnableJpaRepositories(basePackages = "com.oneday")
@EntityScan(basePackages = "com.oneday")
@EnableScheduling
public class OneDayDeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(OneDayDeliveryApplication.class, args);
    }
}
