package com.oneday.grid;

import org.springframework.boot.autoconfigure.SpringBootApplication;

// Minimal @SpringBootConfiguration anchor so @WebMvcTest can find a bootstrap class.
// @WebMvcTest slices out everything non-MVC, so no DB/Kafka/Flyway is started.
@SpringBootApplication
class GridTestApplication {
}
