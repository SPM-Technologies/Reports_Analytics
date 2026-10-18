package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.hospital.patientreg.identity.model.AppUser;
import com.hospital.patientreg.identity.model.Role;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

/** Users, hospitals and audit screens, and signing in with a real password (lock-out included). */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class AdminScreensTest {

    private final JdbcTemplate admin = TestUsers.adminJdbc();

    @Autowired
    private WebApplicationContext context;

    @BeforeAll
    static void setUp() {
        TestUsers.ensureHospital2();
    }

    /** A super administrator working in H001 (the bootstrap "admin" account looks like this). */
    private static AppUser superAdmin() {
        Role r = Role.ADMIN;
        return new AppUser(admin0(), "admin", "Administrator", null, true, true, 0,
                List.of(new HospitalAccess(1, "H001", "Main Hospital", r.name(), r.permissions())));
    }

    private static long admin0() {
        Long id = TestUsers.adminJdbc().queryForObject("SELECT user_id FROM identity.app_user WHERE username = 'admin'", Long.class);
        return id == null ? 0 : id;
    }

    private MockMvc as(AppUser user) {
        return TestUsers.mvc(context, user);
    }

    private static String unique(String prefix) {
        return prefix + ThreadLocalRandom.current().nextInt(100_000, 999_999);
    }

    @Test
    void screensRender() throws Exception {
        as(superAdmin()).perform(get("/admin/users")).andExpect(status().isOk()).andExpect(view().name("admin/users"));
        as(superAdmin()).perform(get("/admin/users/new")).andExpect(status().isOk()).andExpect(view().name("admin/user-form"));
        as(superAdmin()).perform(get("/admin/hospitals")).andExpect(status().isOk()).andExpect(view().name("admin/hospitals"));
        as(superAdmin()).perform(get("/admin/audit")).andExpect(status().isOk()).andExpect(view().name("admin/audit"));
        as(superAdmin()).perform(get("/admin/audit").param("action", "LOGIN").param("user", "admin")).andExpect(status().isOk());
        as(TestUsers.admin1()).perform(get("/admin/audit")).andExpect(status().isOk());
        as(TestUsers.admin1()).perform(get("/account/password")).andExpect(status().isOk());
    }

    @Test
    void hospitalAdministratorManagesOnlyTheirHospitalsRoles() throws Exception {
        String username = unique("t.user");
        as(TestUsers.admin1()).perform(post("/admin/users/new").param("username", username).param("fullName", "Test User")
                        .param("password", "Secret123").param("roles[1]", "REGISTRATION").param("roles[2]", "ADMIN"))
                .andExpect(redirectedUrl("/admin/users"));
        // the H002 role was ignored: H001's administrator cannot hand out H002 roles
        List<String> roles = admin.queryForList("SELECT uh.hospital_id || ':' || uh.role FROM identity.app_user_hospital uh "
                + "JOIN identity.app_user u USING (user_id) WHERE u.username = ?", String.class, username);
        assertThat(roles).containsExactly("1:REGISTRATION");
        String hash = admin.queryForObject("SELECT password_hash FROM identity.app_user WHERE username = ?", String.class, username);
        assertThat(hash).startsWith("$2a$").doesNotContain("Secret123");
        // H002's reports user cannot see or edit H001's user
        long id = admin.queryForObject("SELECT user_id FROM identity.app_user WHERE username = ?", Long.class, username);
        as(TestUsers.reports2()).perform(get("/admin/users/" + id)).andExpect(status().isForbidden());
    }

    @Test
    void invalidInputIsExplainedAndNothingIsSaved() throws Exception {
        String username = unique("t.weak");
        as(TestUsers.admin1()).perform(post("/admin/users/new").param("username", username).param("fullName", "Weak")
                        .param("password", "short").param("roles[1]", "REPORTS"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("Password")));
        as(TestUsers.admin1()).perform(post("/admin/users/new").param("username", "Bad Name!").param("fullName", "X")
                        .param("password", "Secret123").param("roles[1]", "REPORTS"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("Username")));
        as(TestUsers.admin1()).perform(post("/admin/users/new").param("username", unique("t.norole")).param("fullName", "X")
                        .param("password", "Secret123"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("role")));
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.app_user WHERE username = ?", Long.class, username)).isZero();
    }

    @Test
    void onlySuperAdministratorsAddHospitalsWhichGetDepartmentsAndTheirCreatorAsAdministrator() throws Exception {
        String code = "T" + ThreadLocalRandom.current().nextInt(10_000, 99_999);
        as(TestUsers.admin1()).perform(post("/admin/hospitals/new").param("code", code).param("name", "Nope"))
                .andExpect(status().isForbidden());
        as(superAdmin()).perform(post("/admin/hospitals/new").param("code", code.toLowerCase()).param("name", "Test Clinic")
                .param("city", "Nashik")).andExpect(redirectedUrl("/admin/hospitals"));
        Integer id = admin.queryForObject("SELECT hospital_id FROM identity.hospital WHERE hospital_code = ?", Integer.class, code);
        assertThat(admin.queryForObject("SELECT count(*) FROM department WHERE hospital_id = ?", Long.class, id)).isEqualTo(10);
        assertThat(admin.queryForObject("SELECT role FROM identity.app_user_hospital WHERE hospital_id = ? AND user_id = ?",
                String.class, id, admin0())).isEqualTo("ADMIN");
        as(superAdmin()).perform(post("/admin/hospitals/new").param("code", code).param("name", "Again"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("already used")));
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private String newUser(String role) throws Exception {
        String username = unique("t.login");
        as(TestUsers.admin1()).perform(post("/admin/users/new").param("username", username).param("fullName", "Login Test")
                .param("password", "Secret123").param("roles[1]", role))
                .andExpect(redirectedUrl("/admin/users"));
        return username;
    }

    @Test
    void wrongPasswordsBlockOnlyThatComputerAndRevealNothing() throws Exception {
        String username = newUser("REPORTS");
        MockMvc anonymous = TestUsers.anonymous(context);
        anonymous.perform(post("/login").with(csrf()).with(from("10.0.0.5")).param("username", username).param("password", "Secret123"))
                .andExpect(redirectedUrl("/"));
        for (int i = 0; i < 5; i++) {
            anonymous.perform(post("/login").with(csrf()).with(from("10.0.0.5")).param("username", username).param("password", "wrong-" + i))
                    .andExpect(redirectedUrl("/login?error=bad"));
        }
        // that computer may not try this user for a while, even with the right password ...
        anonymous.perform(post("/login").with(csrf()).with(from("10.0.0.5")).param("username", username).param("password", "Secret123"))
                .andExpect(redirectedUrl("/login?error=throttled"));
        // ... but the user can still sign in from another computer: an attacker cannot lock anyone out
        anonymous.perform(post("/login").with(csrf()).with(from("10.0.0.6")).param("username", username).param("password", "Secret123"))
                .andExpect(redirectedUrl("/"));
        // an unknown username gets exactly the same answers (no username enumeration)
        anonymous.perform(post("/login").with(csrf()).with(from("10.0.0.7")).param("username", "no.such.user").param("password", "x"))
                .andExpect(redirectedUrl("/login?error=bad"));
        // five wrong passwords and the refused (throttled) attempt are all audited
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.audit_log WHERE action = 'LOGIN_FAILED' AND username = ? "
                + "AND detail = 'bad'", Long.class, username)).isEqualTo(5);
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.audit_log WHERE action = 'LOGIN_FAILED' AND username = ? "
                + "AND detail = 'throttled'", Long.class, username)).isEqualTo(1);
        // an administrator lifts the block
        long id = admin.queryForObject("SELECT user_id FROM identity.app_user WHERE username = ?", Long.class, username);
        as(TestUsers.admin1()).perform(post("/admin/users/" + id + "/unlock")).andExpect(status().is3xxRedirection());
        anonymous.perform(post("/login").with(csrf()).with(from("10.0.0.5")).param("username", username).param("password", "Secret123"))
                .andExpect(redirectedUrl("/"));
        // the sign-in form itself needs the CSRF token
        anonymous.perform(post("/login").param("username", username).param("password", "Secret123"))
                .andExpect(status().isForbidden());
    }

    /** Signs in with the password and returns the session (cookies) of that browser. */
    private MockHttpSession signIn(String username, String password) throws Exception {
        return (MockHttpSession) TestUsers.anonymous(context).perform(post("/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(redirectedUrl("/")).andReturn().getRequest().getSession();
    }

    @Test
    void takingAccessAwayWorksAtOnceInOpenSessions() throws Exception {
        String username = newUser("REGISTRATION");
        long id = admin.queryForObject("SELECT user_id FROM identity.app_user WHERE username = ?", Long.class, username);
        MockHttpSession session = signIn(username, "Secret123");
        MockMvc browser = TestUsers.anonymous(context);
        browser.perform(get("/patient/new").session(session)).andExpect(status().isOk());

        // the administrator changes the role: the open session follows on its next request
        as(TestUsers.admin1()).perform(post("/admin/users/" + id).param("fullName", "Login Test").param("active", "true")
                .param("roles[1]", "REPORTS")).andExpect(redirectedUrl("/admin/users"));
        browser.perform(get("/patient/new").session(session)).andExpect(status().isForbidden());

        // the administrator deactivates the user: the open session ends
        as(TestUsers.admin1()).perform(post("/admin/users/" + id).param("fullName", "Login Test").param("_active", "on")
                .param("roles[1]", "REPORTS")).andExpect(redirectedUrl("/admin/users"));
        browser.perform(get("/").session(session)).andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void aPasswordResetEndsOtherSessions() throws Exception {
        String username = newUser("REPORTS");
        long id = admin.queryForObject("SELECT user_id FROM identity.app_user WHERE username = ?", Long.class, username);
        MockHttpSession session = signIn(username, "Secret123");
        MockMvc browser = TestUsers.anonymous(context);
        browser.perform(get("/").session(session)).andExpect(status().isOk());
        as(TestUsers.admin1()).perform(post("/admin/users/" + id).param("fullName", "Login Test").param("active", "true")
                .param("password", "NewSecret456").param("roles[1]", "REPORTS")).andExpect(redirectedUrl("/admin/users"));
        browser.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
        signIn(username, "NewSecret456");
    }

    @Test
    void hospitalsDefineTheirOwnRoles() throws Exception {
        String label = unique("Night desk ");
        as(TestUsers.admin1()).perform(post("/admin/roles/new").param("hospitalId", "1").param("label", label)
                .param("permissions", "REGISTER").param("permissions", "ANALYTICS")).andExpect(redirectedUrl("/admin/roles"));
        String key = admin.queryForObject("SELECT role_key FROM identity.role WHERE label = ?", String.class, label);
        assertThat(key).startsWith("H1_NIGHT_DESK_");
        assertThat(admin.queryForObject("SELECT permissions::text FROM identity.role WHERE role_key = ?", String.class, key))
                .isEqualTo("{ANALYTICS,REGISTER}");
        // the role is offered and works for that hospital
        String username = newUser(key);
        MockHttpSession session = signIn(username, "Secret123");
        MockMvc browser = TestUsers.anonymous(context);
        browser.perform(get("/patient/new").session(session)).andExpect(status().isOk());
        browser.perform(get("/reports/register").session(session)).andExpect(status().isForbidden());   // no LEGACY_REPORTS
        // not usable in another hospital, not deletable while in use, not editable by another hospital
        as(superAdmin()).perform(post("/admin/users/new").param("username", unique("t.x")).param("fullName", "X")
                .param("password", "Secret123").param("roles[2]", key))
                .andExpect(status().isOk()).andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("Unknown role")));
        as(TestUsers.admin1()).perform(post("/admin/roles/" + key + "/delete")).andExpect(status().is3xxRedirection());
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.role WHERE role_key = ?", Long.class, key)).isEqualTo(1);
        as(TestUsers.reports2()).perform(get("/admin/roles/" + key)).andExpect(status().isForbidden());
        // built-in roles cannot be edited
        as(TestUsers.admin1()).perform(get("/admin/roles/ADMIN")).andExpect(status().isNotFound());
    }

    @Test
    void superAdministratorsNeedAHospitalToo() throws Exception {
        as(superAdmin()).perform(post("/admin/users/new").param("username", unique("t.super")).param("fullName", "No Hospital")
                        .param("password", "Secret123").param("superAdmin", "true"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("role")));
    }
}
