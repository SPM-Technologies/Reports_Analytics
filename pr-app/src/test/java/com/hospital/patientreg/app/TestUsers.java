package com.hospital.patientreg.app;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.hospital.patientreg.identity.model.AppUser;
import com.hospital.patientreg.identity.model.Role;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Signed-in test users and test database helpers. Requests go through the real Spring Security filter
 * chain (authentication, CSRF, the Tenant filter); the user is put into the session directly instead of
 * signing in with a password. Hospital 1 (H001) always exists; hospital 2 (H002) is added by {@link #ensureHospital2()}.
 */
final class TestUsers {

    static final int H1 = 1;
    static final int H2 = 2;

    static {
        // set-up code may connect before Spring starts; Windows' legacy zone id is rejected by PostgreSQL
        if ("Asia/Calcutta".equals(java.util.TimeZone.getDefault().getID())) {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        }
    }

    private TestUsers() {
    }

    /** Hospital administrator of H001 only (all permissions there). The default user of most tests. */
    static AppUser admin1() {
        return user(9001, "test.admin1", h(H1, Role.ADMIN));
    }

    /** Reports and analytics at H002 only. */
    static AppUser reports2() {
        return user(9002, "test.reports2", h(H2, Role.REPORTS));
    }

    /** Analyst at H002: may share reports, dashboards and formulas with the hospital. */
    static AppUser analyst2() {
        return user(9006, "test.analyst2", h(H2, Role.ANALYST));
    }

    /** Registration desk at H002 only. */
    static AppUser registration2() {
        return user(9003, "test.reg2", h(H2, Role.REGISTRATION));
    }

    /** Reports and analytics at H001 and H002 (a group user). */
    static AppUser group() {
        return user(9004, "test.group", h(H1, Role.REPORTS), h(H2, Role.REPORTS));
    }

    /** Reports and analytics at H001 only (no registration, so mobiles are masked and patients cannot be opened). */
    static AppUser reports1() {
        return user(9005, "test.reports1", h(H1, Role.REPORTS));
    }

    private static HospitalAccess h(int id, Role role) {
        return new HospitalAccess(id, id == H1 ? "H001" : "H002", id == H1 ? "Main Hospital" : "Test Hospital Two",
                role.name(), role.permissions());
    }

    /** Hospital administrator of H002 only. */
    static AppUser admin2() {
        return user(9008, "test.admin2", h(H2, Role.ADMIN));
    }

    /** A user holding a hospital's own (custom) role with the given permissions. */
    static AppUser withRole(long id, String username, int hospitalId, String roleKey,
                            java.util.Set<com.hospital.patientreg.shared.tenant.Permission> permissions) {
        return user(id, username, new HospitalAccess(hospitalId, hospitalId == H1 ? "H001" : "H002",
                hospitalId == H1 ? "Main Hospital" : "Test Hospital Two", roleKey, permissions));
    }

    private static AppUser user(long id, String username, HospitalAccess... hospitals) {
        return new AppUser(id, username, "Test " + username, null, false, true, 0, new ArrayList<>(List.of(hospitals)));
    }

    /** Signs the request in as {@code user} (with a valid CSRF token). */
    static RequestPostProcessor as(AppUser user) {
        return request -> csrf().postProcessRequest(authentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities())).postProcessRequest(request));
    }

    /** MockMvc with the security filter chain, where every request is made by {@code user} unless a test says otherwise. */
    static MockMvc mvc(WebApplicationContext context, AppUser user) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").with(as(user))).build();
    }

    /** MockMvc with the security filter chain and nobody signed in. */
    static MockMvc anonymous(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * Administrator connection to the test database, for setting up and checking data. The application's own
     * pool is subject to row-level security and, outside a request, sees nothing.
     */
    static JdbcTemplate adminJdbc() {
        return new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(),
                "jdbc:postgresql://localhost:5432/prtest?currentSchema=patientreg", "postgres", "Pass@1234"));
    }

    /** Adds the second test hospital (with its default departments) if it is not there yet. */
    static void ensureHospital2() {
        JdbcTemplate admin = adminJdbc();
        admin.update("INSERT INTO identity.hospital (hospital_id, hospital_code, name, city) VALUES (2, 'H002', 'Test Hospital Two', 'Pune') "
                + "ON CONFLICT (hospital_id) DO NOTHING");
        admin.queryForList("SELECT setval(pg_get_serial_sequence('identity.hospital', 'hospital_id'), "
                + "greatest((SELECT max(hospital_id) FROM identity.hospital), 1))");
        admin.queryForList("SELECT patientreg.seed_departments(2)::text");
    }

    /** A department id of the hospital (default departments: GEN = General Medicine). */
    static int generalMedicine(int hospitalId) {
        Integer id = adminJdbc().queryForObject("SELECT dept_id FROM department WHERE hospital_id = ? AND dept_code = 'GEN'",
                Integer.class, hospitalId);
        return id == null ? 0 : id;
    }
}
