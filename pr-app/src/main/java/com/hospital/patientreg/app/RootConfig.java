package com.hospital.patientreg.app;

import com.hospital.patientreg.identity.config.IdentityConfig;
import com.hospital.patientreg.registration.config.RegistrationConfig;
import com.hospital.patientreg.reporting.config.ReportingConfig;
import com.hospital.patientreg.shared.SharedConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Root context: brings together each module's own configuration. */
@Configuration
@Import({SharedConfig.class, IdentityConfig.class, RegistrationConfig.class, ReportingConfig.class})
public class RootConfig {

    /** Applies the database scripts of all modules before any bean (and so any connection pool) is created. */
    @Bean
    public static SchemaMigrator schemaMigrator() {
        return new SchemaMigrator();
    }
}
