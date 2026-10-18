package com.hospital.patientreg.registration.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.TimeZone;
import com.hospital.patientreg.shared.tenant.TenantAwareDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Registration module (root context): the live PostgreSQL (OLTP) pool (application role, row-level security;
 * the schema itself is applied by pr-app's SchemaMigrator with the administrator account),
 * JDBC templates, transactions, services and DAOs. The OLTP beans are {@code @Primary} so that
 * other modules' data sources (e.g. ClickHouse for reporting) never make by-type injection ambiguous.
 */
@Configuration
@EnableTransactionManagement
@PropertySource("classpath:db.properties")
@ComponentScan(basePackages = {
        "com.hospital.patientreg.registration.service",
        "com.hospital.patientreg.registration.dao"})
public class RegistrationConfig {

    static {
        // Windows reports the legacy zone id "Asia/Calcutta", which newer PostgreSQL rejects at login.
        if ("Asia/Calcutta".equals(TimeZone.getDefault().getID())) {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        }
    }

    @Bean(destroyMethod = "close")
    @Primary
    public DataSource dataSource(@Value("${db.url}") String url,
                                 @Value("${db.username}") String username,
                                 @Value("${db.password}") String password,
                                 @Value("${db.pool.maxSize}") int maxSize) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxSize);
        config.setPoolName("patientreg-pool");
        // every borrowed connection carries the signed-in user's hospitals for row-level security
        return new TenantAwareDataSource(new HikariDataSource(config));
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
