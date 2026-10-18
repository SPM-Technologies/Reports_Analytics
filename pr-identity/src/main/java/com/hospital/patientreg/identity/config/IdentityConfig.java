package com.hospital.patientreg.identity.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;

/**
 * Identity module (root context): hospitals, users and roles, login, the request Tenant and the audit log.
 * Uses the application's primary PostgreSQL pool by type; it does not depend on any other module.
 * Controllers (identity.web) are scanned by the servlet context.
 */
@Configuration
@PropertySource("classpath:identity.properties")
@ComponentScan(basePackages = {
        "com.hospital.patientreg.identity.service",
        "com.hospital.patientreg.identity.dao",
        "com.hospital.patientreg.identity.security"})
@Import(SecurityConfig.class)
public class IdentityConfig {
}
