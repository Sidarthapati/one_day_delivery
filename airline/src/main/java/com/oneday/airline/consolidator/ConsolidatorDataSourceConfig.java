package com.oneday.airline.consolidator;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Wires the read-only connection to the freight consolidator's (mocked) production database — a
 * genuinely separate schema, {@code consolidator_mock}, reached through its own {@link DataSource}
 * and {@link Flyway} history at {@code classpath:db/migration-consolidator}, distinct from the
 * app's own {@code db/migration}. Nothing here is JPA-managed: we don't own that schema in reality,
 * so plain JDBC ({@link JdbcTemplate}) is the honest access pattern, mirroring how a real read-only
 * partner-DB integration would be built.
 *
 * <p>Declaring a second {@code DataSource} bean makes Spring Boot's {@code @ConditionalOnMissingBean}
 * autoconfiguration skip creating its own primary one — so the primary {@code DataSource} (every
 * other module's JPA connection) is re-declared here explicitly and marked {@link Primary}, using the
 * same {@code spring.datasource.*} properties Spring Boot would have bound anyway. The consolidator's
 * {@link Flyway} is deliberately never exposed as a {@code @Bean} (same conditional trap) — it's built
 * and migrated inline inside the bean method below, before the {@code DataSource} is ever handed to a
 * consumer; that runs during singleton bean instantiation, the same phase Spring Boot's own primary
 * Flyway integration uses, which completes before the embedded web server starts accepting requests.
 * An {@code ApplicationRunner}/{@code CommandLineRunner} would run only after the server is already
 * listening, risking requests served against an unmigrated schema.
 */
@Configuration
class ConsolidatorDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    DataSource primaryDataSource(@Qualifier("primaryDataSourceProperties") DataSourceProperties primaryDataSourceProperties) {
        return primaryDataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    @ConfigurationProperties("consolidator.datasource")
    DataSourceProperties consolidatorDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    DataSource consolidatorDataSource(
            @Qualifier("consolidatorDataSourceProperties") DataSourceProperties consolidatorDataSourceProperties) {
        DataSource dataSource = consolidatorDataSourceProperties.initializeDataSourceBuilder().build();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration-consolidator")
                .schemas("consolidator_mock")
                .load()
                .migrate();
        return dataSource;
    }

    @Bean
    JdbcTemplate consolidatorJdbcTemplate(@Qualifier("consolidatorDataSource") DataSource consolidatorDataSource) {
        return new JdbcTemplate(consolidatorDataSource);
    }
}
