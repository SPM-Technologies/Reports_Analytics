package com.hospital.patientreg.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/**
 * Applies the database schema at startup, before any connection pool exists (it runs as a bean factory
 * post-processor, i.e. before beans are created). Uses the administrator account ({@code db.admin.*}) to
 * <ol>
 *   <li>create or update the application role ({@code db.username}/{@code db.password}): a normal role, so
 *       PostgreSQL row-level security applies to every application connection;</li>
 *   <li>run each module's script in dependency order, each as ONE statement batch (so DO blocks work):
 *       identity (hospitals, users) &rarr; registration (patients, row-level security) &rarr; reporting
 *       (saved reports) &rarr; grants for the application role.</li>
 * </ol>
 * Every script is idempotent. This is the only migration mechanism (a tool such as Flyway can replace it
 * with the Spring Boot upgrade).
 */
public class SchemaMigrator implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);
    private static final Pattern ROLE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final List<String> SCRIPTS = List.of("identity-schema.sql", "schema.sql", "reporting-schema.sql", "app-grants.sql");

    private Environment env;

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Windows reports the legacy zone id "Asia/Calcutta", which newer PostgreSQL rejects at login.
        if ("Asia/Calcutta".equals(TimeZone.getDefault().getID())) {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        }
        String url = env.getRequiredProperty("db.url");
        String appRole = env.getRequiredProperty("db.username");
        if (!ROLE.matcher(appRole).matches()) {
            throw new IllegalStateException("db.username must be a plain lower-case role name: " + appRole);
        }
        Properties admin = new Properties();
        admin.setProperty("user", env.getRequiredProperty("db.admin.username"));
        admin.setProperty("password", env.getRequiredProperty("db.admin.password"));
        long start = System.currentTimeMillis();
        try (Connection c = connectWithRetry(url, admin)) {
            if (c == null) {
                throw new IllegalStateException("Not a PostgreSQL URL: " + url);
            }
            ensureRole(c, appRole, env.getRequiredProperty("db.password"));
            for (String script : SCRIPTS) {
                try (Statement st = c.createStatement()) {
                    st.execute(read(script).replace("__APP_ROLE__", appRole));
                } catch (SQLException e) {
                    throw new IllegalStateException("Database script " + script + " failed: " + e.getMessage(), e);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot apply the database schema (" + url + "): " + e.getMessage(), e);
        }
        log.info("Database schema applied in {} ms (application role {})", System.currentTimeMillis() - start, appRole);
    }

    /**
     * PostgreSQL may still be starting (e.g. after a Windows restart all containers start at once, or it is in
     * crash recovery): retries for about a minute before giving up, instead of leaving an undeployed application.
     */
    private static Connection connectWithRetry(String url, Properties admin) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                return new org.postgresql.Driver().connect(url, admin);
            } catch (SQLException e) {
                last = e;
                if ("28P01".equals(e.getSQLState()) || "3D000".equals(e.getSQLState())) {
                    throw e;   // wrong password or database: waiting will not help
                }
                log.warn("Database not reachable yet (attempt {}/30): {}", attempt, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /** Creates the login role, or brings an existing one back to the configured password and attributes. */
    private static void ensureRole(Connection c, String role, String password) throws SQLException {
        boolean exists;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        }
        String ddl;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT format(? || ' ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE', ?, ?)")) {
            ps.setString(1, exists ? "ALTER" : "CREATE");
            ps.setString(2, role);
            ps.setString(3, password);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                ddl = rs.getString(1);
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    private static String read(String classpathFile) {
        try (InputStream in = new ClassPathResource(classpathFile).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing database script " + classpathFile, e);
        }
    }
}
