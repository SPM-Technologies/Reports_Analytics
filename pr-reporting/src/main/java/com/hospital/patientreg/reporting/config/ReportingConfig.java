package com.hospital.patientreg.reporting.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.ZoneId;
import com.hospital.patientreg.shared.tenant.TenantAwareDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Reporting module (root context): the ClickHouse analytics pool (read-only account), a small
 * PostgreSQL pool for the module's own {@code reporting} schema (saved reports), the dataset catalogue,
 * the query engine and downloads. No transaction manager: ClickHouse is not transactional and the
 * metadata writes are single statements; the registration module's OLTP beans are {@code @Primary},
 * so nothing here changes their wiring. This module never touches the registration tables.
 */
@Configuration
@PropertySource("classpath:reporting.properties")
@ComponentScan(basePackages = {
        "com.hospital.patientreg.reporting.semantic",
        "com.hospital.patientreg.reporting.query",
        "com.hospital.patientreg.reporting.export",
        "com.hospital.patientreg.reporting.saved",
        "com.hospital.patientreg.reporting.custom"})
public class ReportingConfig {

    /**
     * 2-3 connections to PostgreSQL, used only for the reporting schema (saved reports), with the application
     * role. The schema and the built-in templates are applied by pr-app's SchemaMigrator.
     */
    @Bean(destroyMethod = "close")
    @Qualifier("reportingMeta")
    public DataSource reportingMetaDataSource(@Value("${app.reporting.meta.url}") String url,
                                              @Value("${app.reporting.meta.username}") String username,
                                              @Value("${app.reporting.meta.password}") String password,
                                              @Value("${app.reporting.meta.pool.maxSize}") int maxSize) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(1);
        config.setPoolName("reporting-meta-pool");
        // the user's hospitals and user id travel with every connection: row-level security (reporting-schema.sql)
        return new TenantAwareDataSource(new HikariDataSource(config));
    }

    @Bean
    @Qualifier("reportingMetaJdbc")
    public NamedParameterJdbcTemplate reportingMetaJdbc(@Qualifier("reportingMeta") DataSource meta) {
        return new NamedParameterJdbcTemplate(meta);
    }

    @Bean(destroyMethod = "close")
    @Qualifier("analytics")
    public DataSource analyticsDataSource(@Value("${app.reporting.clickhouse.url}") String url,
                                          @Value("${app.reporting.clickhouse.username}") String username,
                                          @Value("${app.reporting.clickhouse.password}") String password,
                                          @Value("${app.reporting.clickhouse.pool.maxSize}") int maxSize) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(1);
        config.setPoolName("analytics-pool");
        // The ClickHouse account is read-only (profile readonly=2); the driver refuses any attempt to make
        // a connection read-write, so the pool must declare read-only too or no connection is ever created.
        config.setReadOnly(true);
        // If ClickHouse is down, tell the user within seconds instead of the pool's default 30 s wait.
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        // Start even when ClickHouse is down: registration must never depend on reporting infrastructure.
        // Report queries then fail with a clear "analytics store unavailable" error instead.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    @Qualifier("analyticsJdbc")
    public NamedParameterJdbcTemplate analyticsJdbc(@Qualifier("analytics") DataSource analyticsDataSource) {
        return new NamedParameterJdbcTemplate(analyticsDataSource);
    }

    /** Reads the dataset catalogue files; the web layer has its own JSON setup. */
    @Bean
    public ObjectMapper reportingObjectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    /** "Today" and relative periods are computed in IST, like the rest of the application. */
    @Bean
    public Clock reportingClock() {
        return Clock.system(ZoneId.of("Asia/Kolkata"));
    }
}
